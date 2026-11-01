package io.mszymanski.orknux.mobile.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mszymanski.orknux.mobile.chat.ChatApi
import io.mszymanski.orknux.mobile.chat.ConversationViewModel
import io.mszymanski.orknux.mobile.chat.TurnEvent
import io.mszymanski.orknux.mobile.chat.TurnListener
import io.mszymanski.orknux.mobile.core.OrknuxClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.CookieJar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The whole voice loop against a real server, with only the microphone replaced
 * by a recording: its transcription model, a chat turn, its speech model and the
 * phone's player are all the real ones. Makes one chat and deletes it.
 *
 * Needs a server with voice models and no sign-in on the device's localhost:
 * `adb reverse tcp:8080 tcp:8080`. Skipped where there is none.
 */
@RunWith(AndroidJUnit4::class)
class VoiceLoopLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    private fun recording(): ShortArray {
        val bytes = context.assets.open("question.wav").use { it.readBytes() }
        val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(pcm.remaining()).also(pcm::get)
    }

    /** Plays the recording once, then a quiet room, at the pace a microphone would. */
    private fun recordedMicrophone(samples: ShortArray) = Microphone { onFrame ->
        val frame = ShortArray(AndroidMicrophone.FRAME_SAMPLES)
        var at = 0
        repeat(100) {
            onFrame(frame.copyOf(), frame.size)
            delay(5)
        }
        while (at < samples.size) {
            val count = minOf(frame.size, samples.size - at)
            samples.copyInto(frame, 0, at, at + count)
            onFrame(frame.copyOf(), count)
            at += count
            delay(5)
        }
        frame.fill(0)
        while (true) {
            onFrame(frame.copyOf(), frame.size)
            delay(20)
        }
    }

    @Test
    fun aSpokenQuestionIsAnsweredOutLoud() = runBlocking {
        val client = OrknuxClient("http://localhost:8080", null, CookieJar.NO_COOKIES)
        val api = ChatApi(client)
        val workspace = runCatching { api.workspaces().firstOrNull { it.canListen && it.canSpeak } }.getOrNull()
        assumeTrue("needs a local server with voice models", workspace != null)
        val speech = SpeechApi(client, workspace!!.id)

        val played = mutableListOf<Int>()
        val realPlayer = AndroidClipPlayer(target, conversation = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val phases = mutableListOf<VoicePhase>()

        val model = withContext(Dispatchers.Main) { ConversationViewModel(api, workspace.id, null) }
        val session = withContext(Dispatchers.Main) {
            VoiceSession(
                scope = scope,
                microphone = recordedMicrophone(recording()),
                tuning = VoiceTuning.of(workspace),
                chunking = SpeechChunking.of(workspace.voiceSpeechChunking),
                conversation = object : VoiceConversation {
                    override fun say(text: String) = model.sayWhenFree(text)
                    override fun interrupt() = model.stop()
                },
                transcribe = speech::transcribe,
                speak = speech::speak,
                fetchSound = speech::fetch,
                player = { audio, speed ->
                    played += audio.size
                    realPlayer.play(audio, speed)
                },
                initialSpeed = 1f,
            )
        }
        try {
            withContext(Dispatchers.Main) {
                model.listener = object : TurnListener {
                    override fun onTurnBegan() = session.onTurnBegan()
                    override fun onTurnEvent(event: TurnEvent) = session.onTurnEvent(event)
                    override fun onTurnOver() = session.onTurnOver()
                }
                scope.launch { session.ui.collect { if (phases.lastOrNull() != it.phase) phases += it.phase } }
                session.start()
            }
            withTimeout(240_000) {
                session.ui.first { it.phase == VoicePhase.SPEAKING }
                session.ui.first { it.phase == VoicePhase.LISTENING }
            }

            val ui = session.ui.value
            val state = model.state.value
            android.util.Log.i("VoiceLoop", "heard='${ui.caption}' phases=$phases clips=$played answer='${state.lines.lastOrNull()?.content}' trouble=${ui.trouble}")
            assertNull(ui.trouble)
            assertTrue("heard: ${ui.caption}", ui.caption.contains("two", ignoreCase = true) || ui.caption.contains("2"))
            assertEquals(listOf(VoicePhase.LISTENING, VoicePhase.THINKING, VoicePhase.SPEAKING, VoicePhase.LISTENING), phases)
            assertTrue("something was played", played.isNotEmpty() && played.all { it > 0 })
            assertTrue(state.lines.any { it.isUser && it.content == ui.caption })
            assertTrue(state.lines.last().isAnswer)
        } finally {
            withContext(Dispatchers.Main) { session.close() }
            model.state.value.chat?.let { runCatching { api.delete(it.id) } }
            scope.cancel()
        }
    }
}
