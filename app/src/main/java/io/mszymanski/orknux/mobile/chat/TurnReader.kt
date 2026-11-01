package io.mszymanski.orknux.mobile.chat

import io.mszymanski.orknux.mobile.core.ApiException
import io.mszymanski.orknux.mobile.core.SseFrame
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Reads one answer to its last frame, picking it up again where the stream was cut.
 *
 * A phone loses its connection far more often than a browser on a desk does: the
 * turn goes on being written on the server all along. So a stream that ends
 * without `done` or `error` is reopened with `/follow`, which replays the answer
 * from its first frame; what has already been handed on is skipped by position,
 * so nothing is drawn twice. The same rules as the web client's `readToTheEnd`.
 *
 * A refusal before the first frame is not a cut stream, and is thrown.
 *
 * @return whether the end of the turn was heard. False means the caller should
 *   read the history back rather than trust what is on screen.
 */
class TurnReader(
    private val follow: suspend (onFrame: suspend (SseFrame) -> Unit) -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun read(
        first: suspend (onFrame: suspend (SseFrame) -> Unit) -> Unit,
        onEvent: suspend (TurnEvent) -> Unit,
        /** Whether a turn was being read when the stream ended; a follow that found none has nothing to pick up. */
        reading: () -> Boolean = { true },
    ): Boolean {
        var position = 0
        var skip = 0
        var ended = false
        var idle = false
        var announced = false

        val onFrame: suspend (SseFrame) -> Unit = handler@{ frame ->
            val event = frame.toTurnEvent()
            if (event is TurnEvent.Idle) idle = true
            val control = frame.event in CONTROL_FRAMES
            if (!control) {
                position++
                if (position <= skip) return@handler
                if (event is TurnEvent.Done || event is TurnEvent.Failed) ended = true
            }
            if (event is TurnEvent.Following) {
                if (announced) return@handler
                announced = true
            }
            if (event != null) onEvent(event)
        }

        var attempt = 0
        var open: suspend () -> Unit = { first(onFrame) }
        while (true) {
            try {
                open()
                if (ended) return true
                if (idle) return false
            } catch (refused: ApiException) {
                if (attempt == 0) throw refused
            } catch (_: IOException) {
                // Cut: the same as a stream that simply ended.
            }
            if (ended) return true
            if (!reading() || attempt >= REATTACH_ATTEMPTS) return false
            skip = maxOf(skip, position)
            position = 0
            announced = true
            pause(REATTACH_AFTER_MS * (attempt + 1))
            attempt++
            open = { follow(onFrame) }
        }
    }

    private companion object {
        const val REATTACH_ATTEMPTS = 5
        const val REATTACH_AFTER_MS = 1_000L
        val CONTROL_FRAMES = setOf("following", "idle", "waiting")
    }
}
