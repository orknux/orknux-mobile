package io.mszymanski.orknux.mobile.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Where voice hears from; a seam so the session can be tested without a microphone. */
fun interface Microphone {
    /** Calls [onFrame] with 20 ms of 16 kHz mono until cancelled. */
    suspend fun listen(onFrame: suspend (ShortArray, Int) -> Unit)
}

/**
 * The microphone, asked for echo cancellation by name. It stays open while the
 * answer is read aloud, so without it the app hears itself, transcribes itself
 * and sends its own last sentence back as the next thing somebody said.
 */
class AndroidMicrophone : Microphone {
    @SuppressLint("MissingPermission") // Asked for by the screens that open it.
    override suspend fun listen(onFrame: suspend (ShortArray, Int) -> Unit) = withContext(Dispatchers.IO) {
        val least = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(least, FRAME_SAMPLES * 2 * 10),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be opened." }
        val session = record.audioSessionId
        val effects = listOfNotNull(
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session) else null,
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session) else null,
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session) else null,
        )
        effects.forEach { runCatching { it.enabled = true } }
        val frame = ShortArray(FRAME_SAMPLES)
        try {
            record.startRecording()
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = record.read(frame, 0, frame.size)
                if (read < 0) error("The microphone stopped: $read")
                if (read > 0) onFrame(frame, read)
            }
        } finally {
            effects.forEach { runCatching { it.release() } }
            runCatching { record.stop() }
            record.release()
        }
    }

    companion object {
        const val FRAME_SAMPLES = SAMPLE_RATE / 50
    }
}

/** Where a reading plays; a seam for the same reason. */
fun interface ClipPlayer {
    suspend fun play(audio: ByteArray, speed: Float)
}

/** Plays one clip through a temporary file; the audio is listened to once and never kept. */
class AndroidClipPlayer(private val context: Context, private val conversation: Boolean) : ClipPlayer {
    override suspend fun play(audio: ByteArray, speed: Float) {
        val file = withContext(Dispatchers.IO) {
            File.createTempFile("speech", null, context.cacheDir).apply { writeBytes(audio) }
        }
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(if (conversation) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            withContext(Dispatchers.IO) {
                player.setDataSource(file.absolutePath)
                player.prepare()
            }
            if (speed != 1f) runCatching { player.playbackParams = PlaybackParams().setSpeed(speed) }
            suspendCancellableCoroutine { done ->
                player.setOnCompletionListener { if (done.isActive) done.resume(Unit) }
                player.setOnErrorListener { _, what, _ ->
                    if (done.isActive) done.resumeWithException(IllegalStateException("The clip could not be played ($what)."))
                    true
                }
                player.start()
                done.invokeOnCancellation { runCatching { player.stop() } }
            }
        } finally {
            player.release()
            file.delete()
        }
    }
}

/**
 * Puts the phone into a call-like mode, which is what makes its echo canceller
 * work, and sends the sound to a headset if there is one and the loudspeaker
 * otherwise: left alone, this mode plays through the earpiece.
 */
class VoiceRoute(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var modeBefore = AudioManager.MODE_NORMAL
    private var speakerBefore = false

    @Suppress("DEPRECATION")
    fun enter() {
        modeBefore = audio.mode
        speakerBefore = audio.isSpeakerphoneOn
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audio.availableCommunicationDevices
            val chosen = HEADSETS.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            chosen?.let(audio::setCommunicationDevice)
        } else {
            val wired = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSETS }
            audio.isSpeakerphoneOn = !wired
        }
    }

    @Suppress("DEPRECATION")
    fun leave() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice() else audio.isSpeakerphoneOn = speakerBefore
        audio.mode = modeBefore
    }

    private companion object {
        val HEADSETS = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
