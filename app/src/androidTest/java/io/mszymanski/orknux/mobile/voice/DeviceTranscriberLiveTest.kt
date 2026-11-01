package io.mszymanski.orknux.mobile.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The phone's real recogniser on a recording. Skipped on a phone that has none. */
@RunWith(AndroidJUnit4::class)
class DeviceTranscriberLiveTest {
    @Test
    fun thePhoneWritesDownARecording() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val available = DeviceTranscriber.isAvailable(instrumentation.targetContext)
        android.util.Log.i("DeviceEars", "available=$available")
        assumeTrue("needs on-device recognition", available)
        val wav = instrumentation.context.assets.open("question.wav").use { it.readBytes() }
        val heard = DeviceTranscriber(instrumentation.targetContext).transcribe(wav)
        android.util.Log.i("DeviceEars", "heard='$heard'")
        assertTrue("heard: $heard", heard.contains("two", ignoreCase = true) || heard.contains("2"))
    }
}
