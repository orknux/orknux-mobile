package io.mszymanski.orknux.mobile.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * One answer being read aloud, in pieces, while the rest of it is still written.
 *
 * Everything abandoned together - what is queued, in flight and playing - belongs
 * to this object, so cutting in is [stop] on this one and a new one for the next
 * turn. No flag can outlive the turn it belongs to.
 */
class Reading(
    scope: CoroutineScope,
    private val chunking: SpeechChunking,
    private val speak: suspend (String) -> ByteArray,
    private val fetchSound: suspend (String) -> ByteArray,
    private val player: ClipPlayer,
    private val speed: () -> Float,
    private val onPlaying: () -> Unit = {},
    private val onTrouble: (String) -> Unit = {},
    private val onFinished: () -> Unit = {},
) {
    private sealed interface Item {
        data class Piece(val text: String) : Item
        data class Sound(val url: String) : Item
    }

    private val queue = Channel<Item>(Channel.UNLIMITED)
    private var consumed = 0
    private var finished = false

    /** Made one ahead of what is playing, and no further: a stopped answer should not cost its whole length. */
    private val job: Job = scope.launch {
        val audio = Channel<kotlinx.coroutines.Deferred<ByteArray?>>(capacity = 1)
        val maker = launch {
            for (item in queue) {
                audio.send(
                    async {
                        try {
                            when (item) {
                                is Item.Piece -> speak(item.text)
                                is Item.Sound -> fetchSound(item.url)
                            }
                        } catch (stopped: CancellationException) {
                            throw stopped
                        } catch (failure: Exception) {
                            onTrouble(failure.message ?: "That could not be read aloud.")
                            null
                        }
                    },
                )
            }
            audio.close()
        }
        for (made in audio) {
            val clip = made.await() ?: continue
            onPlaying()
            try {
                player.play(clip, speed())
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (failure: Exception) {
                onTrouble(failure.message ?: "That could not be played.")
            }
        }
        maker.join()
        onFinished()
    }

    /** The answer so far, as markdown. [settled] says it ends on a whole utterance. */
    fun push(markdownSoFar: String, settled: Boolean = false) {
        if (!finished) cutFrom(spokenText(markdownSoFar), settled)
    }

    fun sound(url: String) {
        queue.trySend(Item.Sound(url))
    }

    /** No more is coming: what is left is read however it ends. */
    fun finish(markdown: String) {
        if (finished) return
        cutFrom(spokenText(markdown), whole = true)
        finished = true
        queue.close()
    }

    fun stop() {
        finished = true
        queue.close()
        job.cancel()
    }

    private fun cutFrom(text: String, whole: Boolean) {
        val from = consumed.coerceAtMost(text.length)
        val cut = SpeechPieces.cut(text.substring(from), whole, chunking)
        consumed = from + cut.upTo
        cut.parts.forEach { queue.trySend(Item.Piece(it)) }
    }
}
