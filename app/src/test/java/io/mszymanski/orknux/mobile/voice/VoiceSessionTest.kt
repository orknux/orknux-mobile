package io.mszymanski.orknux.mobile.voice

import io.mszymanski.orknux.mobile.chat.TurnEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionTest {

    private class Rig(test: TestScope, tuning: VoiceTuning = VoiceTuning(pauseMs = 100, bargeMs = 100)) : VoiceConversation {
        val said = mutableListOf<String>()
        var interrupts = 0
        val spoken = mutableListOf<String>()
        val played = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        val mic = Channel<Double>(Channel.UNLIMITED)
        val nextWords = ArrayDeque<String>()
        val sound = mutableListOf<String>()

        override fun say(text: String) { said += text }
        override fun interrupt() { interrupts++ }

        val session: VoiceSession = run {
            val dispatcher = UnconfinedTestDispatcher(test.testScheduler)
            VoiceSession(
                scope = test.backgroundScope,
                microphone = { onFrame ->
                    for (level in mic) {
                        val value = (level * 32767).toInt().toShort()
                        onFrame(ShortArray(320) { value }, 320)
                    }
                },
                tuning = tuning,
                chunking = SpeechChunking.SENTENCE,
                conversation = this,
                transcribe = { nextWords.removeFirst() },
                speak = { text -> spoken += text; text.toByteArray() },
                fetchSound = { ByteArray(1) },
                player = { audio, _ ->
                    played += String(audio)
                    gate?.await()
                },
                initialSpeed = 1f,
                thinkingSound = object : ThinkingSound {
                    override fun start() { sound += "start" }
                    override fun stop() { if (sound.lastOrNull() == "start") sound += "stop" }
                    override fun close() { sound += "close" }
                },
                main = dispatcher,
            )
        }

        /** Enough speech and enough quiet to make one whole turn. */
        fun speakATurn(words: String) {
            nextWords += words
            repeat(5) { mic.trySend(0.2) }
            repeat(10) { mic.trySend(0.0) }
        }
    }

    private suspend fun settle(test: TestScope) {
        repeat(50) {
            test.runCurrent()
            yield()
            Thread.sleep(2)
        }
    }

    @Test
    fun aTurnHeardIsSentAndItsAnswerIsReadBack() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.session.start()
        rig.speakATurn("hello there")
        settle(this)
        assertEquals(listOf("hello there"), rig.said)
        assertEquals(VoicePhase.THINKING, rig.session.ui.value.phase)

        rig.session.onTurnBegan()
        rig.session.onTurnEvent(TurnEvent.Chunk("It is **fine**. "))
        rig.session.onTurnEvent(TurnEvent.Chunk("Really."))
        rig.session.onTurnEvent(TurnEvent.Done(1, 0, 0, null))
        rig.session.onTurnOver()
        settle(this)
        assertEquals(listOf("It is fine.", "Really."), rig.spoken)
        assertEquals(VoicePhase.LISTENING, rig.session.ui.value.phase)
        rig.session.close()
    }

    @Test
    fun whatIsSaidWhileItThinksWaitsForTheTurnAfter() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.session.start()
        rig.speakATurn("first")
        settle(this)
        rig.session.onTurnBegan()
        rig.speakATurn("second")
        settle(this)
        assertEquals("second", rig.session.ui.value.waiting)
        assertEquals(listOf("first"), rig.said)

        rig.session.onTurnEvent(TurnEvent.Chunk("Done."))
        rig.session.onTurnOver()
        settle(this)
        assertEquals(listOf("first", "second"), rig.said)
        assertEquals("", rig.session.ui.value.waiting)
        rig.session.close()
    }

    @Test
    fun tappingWhileItThinksDropsTheTurnAndWhatWasWaiting() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.session.start()
        rig.speakATurn("first")
        settle(this)
        rig.session.onTurnBegan()
        rig.speakATurn("second")
        settle(this)

        rig.session.tap()
        assertEquals(1, rig.interrupts)
        assertEquals("", rig.session.ui.value.waiting)

        // The rest of the dropped turn is not read.
        rig.session.onTurnEvent(TurnEvent.Chunk("Too late."))
        rig.session.onTurnOver()
        settle(this)
        assertTrue(rig.spoken.isEmpty())
        assertEquals(VoicePhase.LISTENING, rig.session.ui.value.phase)
        rig.session.close()
    }

    @Test
    fun talkingOverTheAnswerStopsItAndIsTheNextTurn() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.gate = CompletableDeferred()
        rig.session.start()
        rig.speakATurn("first")
        settle(this)
        rig.session.onTurnBegan()
        rig.session.onTurnEvent(TurnEvent.Chunk("A long answer. And it goes on"))
        settle(this)
        assertEquals(VoicePhase.SPEAKING, rig.session.ui.value.phase)

        rig.nextWords += "no not that"
        repeat(10) { rig.mic.trySend(0.2) }
        settle(this)
        assertEquals(1, rig.interrupts)
        assertEquals(VoicePhase.THINKING, rig.session.ui.value.phase)

        repeat(10) { rig.mic.trySend(0.0) }
        settle(this)
        rig.session.onTurnOver()
        settle(this)
        assertEquals(listOf("first", "no not that"), rig.said)
        rig.session.close()
    }

    @Test
    fun thePulsePlaysWhileItThinksAndStopsForTheAnswer() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.gate = CompletableDeferred()
        rig.session.start()
        rig.speakATurn("first")
        settle(this)
        assertEquals("start", rig.sound.last())

        rig.session.onTurnBegan()
        rig.session.onTurnEvent(TurnEvent.Chunk("An answer. And it goes on"))
        settle(this)
        assertEquals(VoicePhase.SPEAKING, rig.session.ui.value.phase)
        assertEquals("stop", rig.sound.last())

        rig.session.close()
        assertEquals("close", rig.sound.last())
    }

    @Test
    fun aMutedRoomSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.session.start()
        rig.session.toggleMute()
        rig.speakATurn("unheard")
        settle(this)
        assertTrue(rig.said.isEmpty())
        rig.session.close()
    }

    @Test
    fun aFailedAnswerSaysSoAndGoesBackToListening() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.session.start()
        rig.speakATurn("first")
        settle(this)
        rig.session.onTurnBegan()
        rig.session.onTurnEvent(TurnEvent.Failed("No provider."))
        rig.session.onTurnOver()
        settle(this)
        assertEquals("No provider.", rig.session.ui.value.trouble)
        assertEquals(VoicePhase.LISTENING, rig.session.ui.value.phase)
        rig.session.close()
    }
}
