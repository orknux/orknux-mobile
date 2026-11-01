package io.mszymanski.orknux.mobile.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

const val SAMPLE_RATE = 16_000

/** 16 kHz mono PCM WAV: what transcription servers actually decode. */
fun pcmToWav(samples: ShortArray, count: Int = samples.size): ByteArray {
    val dataSize = count * 2
    val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
    buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
    buffer.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
    buffer.put("data".toByteArray()).putInt(dataSize)
    for (i in 0 until count) buffer.putShort(samples[i])
    return buffer.array()
}
