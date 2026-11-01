package io.mszymanski.orknux.mobile.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The phone's real audio track: it starts, loops, stops and starts again. */
@RunWith(AndroidJUnit4::class)
class ThinkingSoundLiveTest {
    @Test
    fun thePulseStartsStopsAndStartsAgain() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // The longest pause on offer: the largest buffer the phone is asked to hold.
            val sound = AndroidThinkingSound(pauseMs = 30_000)
            try {
                sound.start()
                assertTrue(sound.isPlaying)
                Thread.sleep(1_000)
                assertTrue(sound.isPlaying)
                sound.stop()
                assertFalse(sound.isPlaying)
                sound.start()
                assertTrue(sound.isPlaying)
            } finally {
                sound.close()
            }
        }
    }
}
