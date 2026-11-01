package io.mszymanski.orknux.mobile.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The phone's own speech recognition, on the device.
 *
 * Handed the turn already recorded rather than left to open the microphone: a
 * recogniser that owns the microphone would fight voice mode's, which stays open
 * between turns, and would replace its turn-taking with its own. So what counts
 * as a turn is the same whichever engine writes it down. Feeding it audio takes
 * Android 13.
 */
class DeviceTranscriber(private val context: Context) {

    suspend fun transcribe(wav: ByteArray): String {
        if (!isAvailable(context)) error("This phone has no on-device speech recognition. Choose the server in Options.")
        return withTimeout(TIMEOUT_MS) {
            coroutineScope {
                val (read, write) = ParcelFileDescriptor.createPipe()
                launch(Dispatchers.IO) {
                    // Closing is what tells the recogniser the turn is over.
                    ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                        runCatching {
                            // Paced like a stream: a recogniser handed a whole recording at once hears nothing.
                            var at = WAV_HEADER
                            while (at < wav.size) {
                                val count = minOf(CHUNK_BYTES, wav.size - at)
                                out.write(wav, at, count)
                                out.flush()
                                at += count
                                delay(CHUNK_PAUSE_MS)
                            }
                        }
                    }
                }
                try {
                    withContext(Dispatchers.Main) { recognise(read) }
                } finally {
                    read.close()
                }
            }
        }
    }

    private suspend fun recognise(audio: ParcelFileDescriptor): String = suspendCancellableCoroutine { done ->
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        done.invokeOnCancellation {
            // Cancellation can arrive off the main thread, where a recogniser may not be touched.
            android.os.Handler(android.os.Looper.getMainLooper()).post { recognizer.destroy() }
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                recognizer.destroy()
                if (done.isActive) done.resume(text)
            }

            override fun onError(error: Int) {
                val missing = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                // Asked for while the recogniser is still open; Android fetches the pack in the background.
                if (missing) runCatching { recognizer.triggerModelDownload(languageIntent()) }
                recognizer.destroy()
                if (!done.isActive) return
                when (error) {
                    // Nothing recognisable was said: the same as a server transcribing nothing.
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> done.resume("")
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        done.resumeWithException(IllegalStateException("This phone is fetching on-device recognition for ${Locale.getDefault().displayLanguage}. Try again shortly, or choose the server in Options."))
                    else -> done.resumeWithException(IllegalStateException("This phone could not transcribe that ($error)."))
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(
            languageIntent()
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE),
        )
    }

    private fun languageIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())

    companion object {
        private const val WAV_HEADER = 44
        /** A tenth of a second of audio, sent as fast as it was spoken. */
        private const val CHUNK_BYTES = SAMPLE_RATE / 10 * 2
        private const val CHUNK_PAUSE_MS = 100L
        private const val TIMEOUT_MS = 60_000L

        fun isAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }
}
