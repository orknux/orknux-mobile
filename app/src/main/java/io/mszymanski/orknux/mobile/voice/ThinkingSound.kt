package io.mszymanski.orknux.mobile.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** What plays while voice mode thinks; a seam so the session can be tested without speakers. */
interface ThinkingSound {
    fun start()
    fun stop()
    fun close()

    object None : ThinkingSound {
        override fun start() = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }
}

/**
 * One round of the pulse: three soft rising pops, "ty ty ty", then a rest.
 *
 * Quiet on purpose. The microphone stays open while it plays, so anything louder
 * is more for the echo canceller to keep out of what counts as somebody speaking.
 * Made here rather than kept as a file, so there is nothing to license.
 */
fun thinkingPulse(pauseMs: Int, pitchHz: Int = DEFAULT_PITCH_HZ, sampleRate: Int = PULSE_RATE): ShortArray {
    val samples = ShortArray(sampleRate * (POP_STARTS_MS.last() + POP_MS + pauseMs) / 1000)
    POP_STARTS_MS.forEach { startMs ->
        val start = sampleRate * startMs / 1000
        val length = sampleRate * POP_MS / 1000
        var phase = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / length
            phase += 2 * PI * pitchHz * (1 + (POP_RISE - 1) * t) / sampleRate
            // A quick rise so it does not click, and a decay that makes it a bubble rather than a beep.
            val envelope = min(1.0, t * 12) * exp(-5 * t)
            samples[start + i] = (sin(phase) * envelope * PULSE_LEVEL * Short.MAX_VALUE).toInt().toShort()
        }
    }
    return samples
}

const val PULSE_LEVEL = 0.14
/** How long the three pops take, with no pause after them. */
const val PULSE_ROUND_MS = 575L
/** Low for a sound this low: the whole round, silence included, is held in memory. */
const val PULSE_RATE = 22_050
private const val POP_MS = 95
/** Where each pop starts; it rises from there by [POP_RISE]. */
const val DEFAULT_PITCH_HZ = 340
const val DEFAULT_PAUSE_MS = 5_000
private const val POP_RISE = 1.65
private val POP_STARTS_MS = listOf(0, 240, 480)

/** Loops the pulse on the call-like route voice mode uses, which is the one the echo canceller knows about. */
class AndroidThinkingSound(
    pauseMs: Int,
    pitchHz: Int = DEFAULT_PITCH_HZ,
    /** Off voice mode's route, as a preview in Options is, it plays as ordinary media. */
    conversation: Boolean = true,
) : ThinkingSound {
    private val pulse = thinkingPulse(pauseMs, pitchHz)
    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (conversation) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(PULSE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(pulse.size * 2)
        .build()
        .also {
            it.write(pulse, 0, pulse.size)
            it.setLoopPoints(0, pulse.size, -1)
        }

    val isPlaying: Boolean get() = track.playState == AudioTrack.PLAYSTATE_PLAYING

    override fun start() {
        if (isPlaying) return
        runCatching {
            track.reloadStaticData()
            track.setLoopPoints(0, pulse.size, -1)
            track.play()
        }
    }

    override fun stop() {
        if (isPlaying) runCatching { track.pause() }
    }

    override fun close() {
        runCatching { track.stop() }
        track.release()
    }
}
