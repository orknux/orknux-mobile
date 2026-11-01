package io.mszymanski.orknux.mobile.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The phone's real engine and player, with nothing else involved. */
@RunWith(AndroidJUnit4::class)
class DeviceSpeechLiveTest {
    @Test
    fun thePhonesVoiceMakesAClipThePlayerCanPlay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val speech = DeviceSpeech(context)
        try {
            val clip = speech.speak("Two plus two is four.")
            assertEquals("RIFF", String(clip, 0, 4))
            assertTrue("a sentence's worth of audio, not a header: ${clip.size}", clip.size > 8_000)
            AndroidClipPlayer(context, conversation = false).play(clip, 1.5f)
        } finally {
            speech.close()
        }
    }
}
