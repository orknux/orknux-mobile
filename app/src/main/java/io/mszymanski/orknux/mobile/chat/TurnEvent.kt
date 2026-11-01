package io.mszymanski.orknux.mobile.chat

import io.mszymanski.orknux.mobile.core.OrknuxJson
import io.mszymanski.orknux.mobile.core.SseFrame
import kotlinx.serialization.Serializable

/** What the server says about one answer as it is written; the vocabulary of `ChatStreamAPI`. */
sealed interface TurnEvent {
    data class Following(val kept: Int?) : TurnEvent
    data object Idle : TurnEvent
    data object Waiting : TurnEvent
    data class Chunk(val text: String) : TurnEvent
    data class Thinking(val text: String) : TurnEvent
    data class Thought(val text: String, val millis: Long) : TurnEvent
    data class Said(val text: String) : TurnEvent
    data class Call(val at: Int, val tool: String, val arguments: String) : TurnEvent
    data class Called(val at: Int, val result: String, val failed: Boolean) : TurnEvent
    data class Drew(val markdown: String) : TurnEvent
    data class Filed(val attachment: Attachment) : TurnEvent
    data class Play(val url: String) : TurnEvent
    data class Writing(val tool: String?, val chars: Int) : TurnEvent
    data class Reading(val total: Long, val processed: Long) : TurnEvent
    data class Session(val id: String) : TurnEvent
    data class Titled(val title: String) : TurnEvent
    data class PersonRead(val text: String) : TurnEvent
    data object Compacting : TurnEvent
    data object Compacted : TurnEvent
    data class Done(val millis: Long, val inputTokens: Long, val outputTokens: Long, val cost: Double?) : TurnEvent
    data class Failed(val reason: String) : TurnEvent

    /** About the stream rather than the answer: not counted, and never replayed. */
    val isControl: Boolean get() = this is Following || this is Idle || this is Waiting
}

@Serializable
private data class FramePayload(
    val text: String? = null,
    val at: Int? = null,
    val tool: String? = null,
    val arguments: String? = null,
    val result: String? = null,
    val failed: Boolean = false,
    val millis: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cost: Double? = null,
    val reason: String? = null,
    val title: String? = null,
    val markdown: String? = null,
    val url: String? = null,
    val id: String? = null,
    val filename: String? = null,
    val contentType: String? = null,
    val sizeBytes: Double = 0.0,
    val kept: Int? = null,
    val chars: Int = 0,
    val total: Long? = null,
    val processed: Long = 0,
)

/** Null for a frame this client does not draw; those still count as a position in the turn. */
fun SseFrame.toTurnEvent(): TurnEvent? {
    val p = runCatching { OrknuxJson.decodeFromString<FramePayload>(data) }.getOrNull() ?: return null
    return when (event) {
        "following" -> TurnEvent.Following(p.kept)
        "idle" -> TurnEvent.Idle
        "waiting" -> TurnEvent.Waiting
        "chunk" -> p.text?.let(TurnEvent::Chunk)
        "thinking" -> p.text?.let(TurnEvent::Thinking)
        "thought" -> p.text?.let { TurnEvent.Thought(it, p.millis) }
        "said" -> p.text?.let(TurnEvent::Said)
        "call" -> p.at?.let { TurnEvent.Call(it, p.tool.orEmpty(), p.arguments.orEmpty()) }
        "called" -> p.at?.let { TurnEvent.Called(it, p.result.orEmpty(), p.failed) }
        "drew" -> p.markdown?.let(TurnEvent::Drew)
        "filed" -> p.id?.let {
            TurnEvent.Filed(Attachment(it, p.filename.orEmpty(), p.contentType ?: "application/octet-stream", p.sizeBytes))
        }
        "play" -> p.url?.let(TurnEvent::Play)
        "writing" -> TurnEvent.Writing(p.tool, p.chars)
        "reading" -> p.total?.let { TurnEvent.Reading(it, p.processed) }
        "session" -> p.id?.let(TurnEvent::Session)
        "titled" -> p.title?.let(TurnEvent::Titled)
        "personRead" -> p.text?.let(TurnEvent::PersonRead)
        "compacting" -> TurnEvent.Compacting
        "compacted" -> TurnEvent.Compacted
        "done" -> TurnEvent.Done(p.millis, p.inputTokens, p.outputTokens, p.cost)
        "error" -> TurnEvent.Failed(p.reason ?: "The model could not answer.")
        else -> null
    }
}
