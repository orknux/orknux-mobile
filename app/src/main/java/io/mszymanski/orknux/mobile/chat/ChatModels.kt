package io.mszymanski.orknux.mobile.chat

import kotlinx.serialization.Serializable

@Serializable
data class Attachment(
    val id: String,
    val filename: String = "",
    val contentType: String = "application/octet-stream",
    /** A GraphQL Float on the wire: `2048.0`. */
    val sizeBytes: Double = 0.0,
) {
    /** The list the server serves inline: raster pictures, not SVG. */
    val isPicture: Boolean get() = contentType.lowercase() in PICTURES
}

// Not in a companion: a serializable class's companion is the serializer's, and a private one breaks it at run time.
private val PICTURES = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp")

@Serializable
data class ChatSession(
    val id: String,
    val workspaceId: String,
    val title: String = "",
    val pinned: Boolean = false,
    val modelName: String? = null,
    val createdAt: String? = null,
    val lastMessageAt: String? = null,
    val unreadCount: Int = 0,
    val agentId: String? = null,
    val agentName: String? = null,
    val llmSessionId: String? = null,
    val mode: ChatMode = ChatMode.DEFAULT,
) {
    /** What answers this chat, as the top bar says it. */
    val answeredBy: String? get() = agentName ?: modelName
}

@Serializable
enum class ChatMode(val label: String) {
    /** Only ever a chat that already had it: this client starts chats on Auto and does not offer it. */
    DEFAULT("Default"),
    PLAN("Plan"),
    AUTO("Auto"),
    ;

    companion object {
        val OFFERED = listOf(AUTO, PLAN)
    }
}

/**
 * One line of a chat. Role is user, assistant, system, `tool` (a call the agent
 * made, with its arguments as content) or `thought` (one round's thinking).
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String = "",
    val actor: String? = null,
    val thinking: String? = null,
    /** A GraphQL Float on the wire: `3800.0`. */
    val thinkingMillis: Double? = null,
    val at: String? = null,
    val attachments: List<Attachment> = emptyList(),
) {
    val isUser: Boolean get() = role.equals("user", ignoreCase = true)
    val isAnswer: Boolean get() = role.equals("assistant", ignoreCase = true)
    val isCall: Boolean get() = role.equals("tool", ignoreCase = true)
    val isThought: Boolean get() = role.equals("thought", ignoreCase = true)
}

/** How hard the chat's model thinks: null is the model's own setting; no levels is a model that takes none. */
@Serializable
data class ChatEffort(val reasoningEffort: String? = null, val reasoningEfforts: List<String> = emptyList())

@Serializable
data class InteractionOption(
    val id: String,
    val label: String,
    val style: String? = null,
    val description: String? = null,
)

@Serializable
data class InteractionItem(val name: String, val kind: String? = null, val description: String? = null)

/** A question put to the person on behalf of the agent. Drawn from its options alone. */
@Serializable
data class InteractionRequest(
    val id: String,
    val kind: String = "",
    val askedBy: String? = null,
    val question: String = "",
    val options: List<InteractionOption> = emptyList(),
    val items: List<InteractionItem> = emptyList(),
    val chosen: String? = null,
    val multiple: Boolean = false,
    val allowsText: Boolean = false,
) {
    val isOpen: Boolean get() = chosen == null
    val isPlan: Boolean get() = kind == "agent.plan"
}

@Serializable
data class Workspace(
    val id: String,
    val name: String = "",
    val transcriptionModelId: String? = null,
    val speechModelId: String? = null,
    val voicePauseEndsTurnMs: Long? = null,
    val voiceSpeechOverRoomPercent: Int? = null,
    val voiceUnattendedMicrophoneMs: Long? = null,
    val voiceBargeInMs: Long? = null,
    val voiceBargeInWhileThinking: Boolean? = null,
    val voiceSpeechChunking: String? = null,
    val voiceButtonHidden: Boolean = false,
) {
    val canListen: Boolean get() = transcriptionModelId != null
    val canSpeak: Boolean get() = speechModelId != null
}

@Serializable
data class Agent(val id: String, val name: String = "", val enabled: Boolean = true, val modelId: String? = null) {
    val usable: Boolean get() = enabled && modelId != null
}

@Serializable
data class Page<T>(val content: List<T> = emptyList())
