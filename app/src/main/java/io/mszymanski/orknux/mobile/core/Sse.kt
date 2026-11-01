package io.mszymanski.orknux.mobile.core

import okio.BufferedSource

/** One server-sent event, as the protocol spells it. */
data class SseFrame(val event: String, val data: String)

/**
 * Reads frames off a stream until it ends.
 *
 * A frame only counts once its blank line has arrived: half of one parses as
 * nothing, and a stream that is cut mid-frame must not deliver the half.
 */
fun BufferedSource.readSseFrames(onFrame: (SseFrame) -> Unit) {
    var event = "message"
    val data = StringBuilder()
    while (true) {
        val line = readUtf8Line() ?: return
        when {
            line.isEmpty() -> {
                if (data.isNotEmpty()) onFrame(SseFrame(event, data.toString()))
                event = "message"
                data.clear()
            }
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
        }
    }
}
