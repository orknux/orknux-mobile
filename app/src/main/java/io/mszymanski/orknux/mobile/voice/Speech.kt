package io.mszymanski.orknux.mobile.voice

import io.mszymanski.orknux.mobile.core.OrknuxClient
import io.mszymanski.orknux.mobile.core.OrknuxJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/** The workspace's own speech and transcription models; nothing here chooses one. */
class SpeechApi(private val client: OrknuxClient, private val workspaceId: String) {

    suspend fun transcribe(wav: ByteArray): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            // Named, because most of these servers pick a decoder from the extension.
            .addFormDataPart("audio", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val text = client.send(client.request("/api/workspaces/$workspaceId/transcription").post(body).build()) { it.body.string() }
        return OrknuxClient.decoded { OrknuxJson.parseToJsonElement(text).jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    }

    /** Read at its own pace and played faster or slower here: some readers answer silence to any other speed. */
    suspend fun speak(text: String): ByteArray {
        val body = client.jsonBody(buildJsonObject { put("text", text) })
        return client.send(client.request("/api/workspaces/$workspaceId/speech").post(body).build()) { it.body.bytes() }
    }

    suspend fun fetch(url: String): ByteArray {
        val request = if (url.startsWith("/")) client.request(url) else client.request("").url(url)
        return client.send(request.build()) { it.body.bytes() }
    }
}

/** The microphone in the composer: records until a pause or a second press, and hands back the words. */
class Dictation(
    private val scope: CoroutineScope,
    private val microphone: Microphone,
    private val tuning: VoiceTuning,
    private val transcribe: suspend (ByteArray) -> String,
    private val onText: (String) -> Unit,
    private val onTrouble: (String) -> Unit,
) {
    enum class State { IDLE, RECORDING, TRANSCRIBING }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    @Volatile private var stopping = false

    fun press() {
        when (_state.value) {
            State.IDLE -> begin()
            State.RECORDING -> stopping = true
            State.TRANSCRIBING -> Unit
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = State.IDLE
    }

    private fun begin() {
        stopping = false
        _state.value = State.RECORDING
        job = scope.launch {
            try {
                val detector = TurnDetector(tuning)
                val capture = TurnCapture()
                var now = 0L
                var wav: ByteArray? = null
                var finished = false
                try {
                    microphone.listen { frame, count ->
                        now += 20
                        val heard = detector.frame(TurnDetector.loudnessOf(frame, count), now, muted = false, interruptible = false)
                        capture.add(frame, count, kept = detector.anythingSaid)
                        if (heard.turnOver || stopping) {
                            if (detector.anythingSaid) wav = capture.wav()
                            finished = true
                            error("finished")
                        }
                    }
                } catch (stop: IllegalStateException) {
                    // The microphone runs until it is thrown out of; anything else is a real fault.
                    if (!finished) throw stop
                }
                val recorded = wav
                if (recorded != null) {
                    _state.value = State.TRANSCRIBING
                    val text = transcribe(recorded).trim()
                    if (text.isNotEmpty()) onText(text)
                }
            } catch (stopped: kotlinx.coroutines.CancellationException) {
                throw stopped
            } catch (failure: Exception) {
                onTrouble(failure.message ?: "That could not be transcribed.")
            } finally {
                _state.value = State.IDLE
            }
        }
    }
}
