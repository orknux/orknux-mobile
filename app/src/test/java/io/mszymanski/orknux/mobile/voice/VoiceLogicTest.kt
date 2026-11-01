package io.mszymanski.orknux.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceLogicTest {
    private val quiet = 0.001
    private val voice = 0.1

    private fun TurnDetector.run(loudness: Double, from: Long, to: Long, interruptible: Boolean = false, muted: Boolean = false): List<TurnDetector.Heard> =
        (from until to step 20).map { frame(loudness, it, muted, interruptible) }

    @Test
    fun aRoomThatStaysQuietNeverEndsATurn() {
        val detector = TurnDetector(VoiceTuning())
        assertTrue(detector.run(quiet, 0, 60_000).none { it.turnOver })
        assertFalse(detector.anythingSaid)
    }

    @Test
    fun aPauseAfterSpeechEndsTheTurnOnlyOnceItIsLongEnough() {
        val detector = TurnDetector(VoiceTuning(pauseMs = 2_500))
        detector.run(voice, 0, 1_000)
        assertTrue(detector.run(quiet, 1_000, 3_400).none { it.turnOver })
        assertTrue(detector.run(quiet, 3_400, 3_700).any { it.turnOver })
        assertTrue(detector.anythingSaid)
    }

    @Test
    fun theFuseGoesForAMicrophoneNoPauseEverCloses() {
        val detector = TurnDetector(VoiceTuning(unattendedMs = 1_000))
        assertTrue(detector.run(voice, 0, 1_100).last().turnOver)
    }

    @Test
    fun aMutedVoiceIsNotHeard() {
        val detector = TurnDetector(VoiceTuning())
        assertTrue(detector.run(voice, 0, 1_000, muted = true).none { it.aVoice })
        assertFalse(detector.anythingSaid)
    }

    @Test
    fun aCoughDoesNotStopTheAnswerButTalkingOverItDoes() {
        val cough = TurnDetector(VoiceTuning(bargeMs = 1_000))
        cough.run(voice, 0, 200, interruptible = true)
        val after = cough.run(quiet, 200, 1_000, interruptible = true) + cough.run(voice, 1_000, 1_200, interruptible = true)
        assertTrue(after.none { it.bargeIn })

        val talking = TurnDetector(VoiceTuning(bargeMs = 1_000))
        assertTrue(talking.run(voice, 0, 1_100, interruptible = true).any { it.bargeIn })
    }

    @Test
    fun aGapBetweenSyllablesDoesNotRestartTheRun() {
        val detector = TurnDetector(VoiceTuning(bargeMs = 1_000))
        val heard = detector.run(voice, 0, 500, interruptible = true) +
            detector.run(quiet, 500, 700, interruptible = true) +
            detector.run(voice, 700, 1_100, interruptible = true)
        assertTrue(heard.any { it.bargeIn })
    }

    @Test
    fun zeroLeavesTheAnswerAlone() {
        val detector = TurnDetector(VoiceTuning(bargeMs = 0))
        assertTrue(detector.run(voice, 0, 5_000, interruptible = true).none { it.bargeIn })
    }

    @Test
    fun thePulseIsThreeQuietPopsAndARest() {
        val pulse = thinkingPulse(pauseMs = 5_000, sampleRate = 10_000)
        assertEquals(5_750 + 50_000, pulse.size)
        assertTrue("quiet", pulse.maxOf { kotlin.math.abs(it.toInt()) } <= PULSE_LEVEL * Short.MAX_VALUE)
        assertTrue("ends on silence, so the loop does not click", pulse.takeLast(50_000).all { it.toInt() == 0 })
        val pops = listOf(0..949, 2_400..3_349, 4_800..5_749).count { range -> range.any { pulse[it].toInt() != 0 } }
        assertEquals(3, pops)
    }

    @Test
    fun aHigherPitchMeansMoreCyclesInTheSameTime() {
        fun crossings(percent: Int) = thinkingPulse(0, percent).toList().zipWithNext().count { (a, b) -> a < 0 && b >= 0 }
        assertEquals(thinkingPulse(5_000, 80).size, thinkingPulse(5_000, 1_500).size)
        assertTrue(crossings(1_500) > crossings(340) && crossings(340) > crossings(80))
    }

    @Test
    fun wavHeaderIsWhatAWhisperServerExpects() {
        val wav = pcmToWav(shortArrayOf(1, -1))
        assertEquals(48, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16_000, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(4, b.getInt(40))
    }

    @Test
    fun whatIsSpokenIsWhatItRendersTo() {
        assertEquals(
            "Summary\n\nA bold word and a link and name.\nfirst\nsecond",
            spokenText("## Summary\n\nA **bold** word and a [link](https://x.y/z) and `name`.\n- first\n1. second"),
        )
        assertEquals("Before\n$CODE_BLOCK_SAID\nAfter", spokenText("Before\n```kotlin\nval a = 1\n```\nAfter"))
        assertEquals("a, b\n1, 2", spokenText("| a | b |\n|---|---|\n| 1 | 2 |"))
        assertEquals("Half a bold", spokenText("Half a **bold"))
        assertEquals("snake_case stays", spokenText("snake_case stays"))
        assertEquals("", spokenText("![](https://x.y/z.png)\n---"))
    }

    @Test
    fun sentencesGatherUpToOneBreathAndTheRestWaits() {
        val cut = SpeechPieces.cut("One. Two! Three is still go", whole = false, SpeechChunking.SENTENCE)
        assertEquals(listOf("One. Two!"), cut.parts)
        assertEquals("One. Two!".length, cut.upTo)
        assertEquals(listOf("One. Two! Three is still go"), SpeechPieces.cut("One. Two! Three is still go", true, SpeechChunking.SENTENCE).parts)

        val long = "${"a".repeat(200)}. ${"b".repeat(100)}. rest"
        assertEquals(2, SpeechPieces.cut(long, whole = false, SpeechChunking.SENTENCE).parts.size)
    }

    @Test
    fun paragraphsAndNoneCutWhereTheySay() {
        assertEquals(listOf("One. Two."), SpeechPieces.cut("One. Two.\n\nThree", false, SpeechChunking.PARAGRAPH).parts)
        assertTrue(SpeechPieces.cut("One. Two.\n\nThree", false, SpeechChunking.NONE).parts.isEmpty())
        assertEquals(listOf("One. Two.\n\nThree"), SpeechPieces.cut("One. Two.\n\nThree", true, SpeechChunking.NONE).parts)
    }

    @Test
    fun aLineAlreadySpokenIsNotSpokenAgain() {
        val heard = mutableListOf<String>()
        val turn = VoicedTurn { soFar, _ -> heard += soFar }
        turn.said("Checking your decks.")
        turn.answered("Checking")
        assertEquals("still might be the repeat", "Checking your decks.", heard.last())
        turn.answered("Checking your decks. You have three.")
        assertEquals("Checking your decks.\n\n You have three.", heard.last())
        assertEquals("Checking your decks.\n\n You have three.", turn.text())
    }
}
