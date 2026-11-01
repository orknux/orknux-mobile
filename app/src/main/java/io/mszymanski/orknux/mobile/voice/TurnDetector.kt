package io.mszymanski.orknux.mobile.voice

import io.mszymanski.orknux.mobile.chat.Workspace
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** How a turn is taken: the workspace's decisions where it made any, else voice mode's own. */
data class VoiceTuning(
    val pauseMs: Long = 2_500,
    val overRoom: Double = 2.5,
    val unattendedMs: Long = 600_000,
    /** Zero is a decision: leave the answer alone. */
    val bargeMs: Long = 1_000,
    val bargeWhileThinking: Boolean = false,
) {
    companion object {
        fun of(workspace: Workspace?) = VoiceTuning(
            pauseMs = workspace?.voicePauseEndsTurnMs ?: 2_500,
            overRoom = (workspace?.voiceSpeechOverRoomPercent ?: 250) / 100.0,
            unattendedMs = workspace?.voiceUnattendedMicrophoneMs ?: 600_000,
            bargeMs = workspace?.voiceBargeInMs ?: 1_000,
            bargeWhileThinking = workspace?.voiceBargeInWhileThinking ?: false,
        )
    }
}

/**
 * Decides, frame by frame, whether somebody is speaking and whether they have
 * finished. The same reckoning as the web panel's `VoiceMode`, so a workspace's
 * voice settings mean the same thing on a phone.
 */
class TurnDetector(private val tuning: VoiceTuning) {

    data class Heard(
        /** 0 to 1, for the circle. */
        val level: Float,
        val aVoice: Boolean,
        /** Talked over the answer for long enough to mean it. */
        val bargeIn: Boolean,
        /** The turn is over: a pause after speech, or the unattended fuse. */
        val turnOver: Boolean,
    )

    private var floor = SPEECH_LEVEL
    private var startedAt: Long? = null
    private var spokeAt: Long? = null
    private var overAnswerSince: Long? = null
    private var overAnswerLast = 0L

    /** Whether anything was said since the turn began. */
    val anythingSaid: Boolean get() = spokeAt != null

    fun frame(loudness: Double, now: Long, muted: Boolean, interruptible: Boolean): Heard {
        val began = startedAt ?: now.also { startedAt = it }
        floor = if (loudness < floor) loudness else floor * 0.999 + loudness * 0.001

        val aVoice = !muted && (loudness > max(floor * tuning.overRoom, VISIBLE_LEVEL) || loudness > SPEECH_LEVEL)
        if (aVoice) spokeAt = now

        var bargeIn = false
        if (!interruptible || tuning.bargeMs <= 0) {
            overAnswerSince = null
        } else if (aVoice) {
            // A run of talking, not of frames: speech dips between syllables, a cough is followed by quiet.
            val since = overAnswerSince?.takeIf { now - overAnswerLast <= tuning.bargeMs / 2 } ?: now
            overAnswerSince = since
            overAnswerLast = now
            if (now - since >= tuning.bargeMs) {
                overAnswerSince = null
                bargeIn = true
            }
        }

        val spoke = spokeAt
        val quietLongEnough = spoke != null && now - spoke > tuning.pauseMs
        val goneOnTooLong = now - began > tuning.unattendedMs
        return Heard(
            level = if (loudness < VISIBLE_LEVEL) 0f else min(1.0, loudness * 6).toFloat(),
            aVoice = aVoice,
            bargeIn = bargeIn,
            turnOver = quietLongEnough || goneOnTooLong,
        )
    }

    /** The next turn starts here; the room is still the room. */
    fun nextTurn() {
        startedAt = null
        spokeAt = null
    }

    companion object {
        private const val SPEECH_LEVEL = 0.02
        private const val VISIBLE_LEVEL = 0.01

        fun loudnessOf(samples: ShortArray, count: Int): Double {
            if (count <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until count) {
                val sample = samples[i] / 32768.0
                sum += sample * sample
            }
            return sqrt(sum / count)
        }
    }
}
