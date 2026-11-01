package io.mszymanski.orknux.mobile.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Which voice reads answers aloud. */
enum class SpeechEngine { SERVER, DEVICE }

/**
 * The phone's own text-to-speech.
 *
 * Synthesised to a clip rather than spoken directly, so it goes through the same
 * [Reading] and [ClipPlayer] as the server's: the same queue, stop and speed,
 * and the same audio route the echo canceller depends on.
 */
class DeviceSpeech(private val context: Context) {
    private val ready = CompletableDeferred<TextToSpeech>()
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var engine: TextToSpeech? = null

    private fun start() {
        if (engine != null) return
        engine = TextToSpeech(context.applicationContext) { status ->
            val started = engine
            if (status == TextToSpeech.SUCCESS && started != null) {
                val language = started.setLanguage(Locale.getDefault())
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // The engine's own default voice beats silence.
                    started.setLanguage(started.defaultVoice?.locale ?: Locale.ENGLISH)
                }
                started.setOnUtteranceProgressListener(listener)
                ready.complete(started)
            } else {
                ready.completeExceptionally(IllegalStateException(NO_ENGINE))
            }
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String) = Unit
        override fun onDone(id: String) {
            waiting.remove(id)?.complete(Unit)
        }

        @Deprecated("Required by the abstract class")
        override fun onError(id: String) {
            waiting.remove(id)?.completeExceptionally(IllegalStateException(COULD_NOT_READ))
        }

        override fun onError(id: String, errorCode: Int) {
            waiting.remove(id)?.completeExceptionally(IllegalStateException("$COULD_NOT_READ ($errorCode)"))
        }
    }

    suspend fun speak(text: String): ByteArray = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) { start() }
        val file = File.createTempFile("device-speech", ".wav", context.cacheDir)
        val id = UUID.randomUUID().toString()
        try {
            withTimeout(TIMEOUT_MS) {
                val tts = ready.await()
                val done = CompletableDeferred<Unit>()
                waiting[id] = done
                check(tts.synthesizeToFile(text, null, file, id) == TextToSpeech.SUCCESS) { COULD_NOT_READ }
                done.await()
            }
            file.readBytes()
        } finally {
            waiting.remove(id)
            file.delete()
        }
    }

    fun close() {
        engine?.shutdown()
        engine = null
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val NO_ENGINE = "This phone has no text-to-speech engine ready. Choose the server's voice in Options."
        const val COULD_NOT_READ = "This phone's voice could not read that."
    }
}
