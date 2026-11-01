package io.mszymanski.orknux.mobile.chat

import io.mszymanski.orknux.mobile.core.OrknuxJson
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class CachedChat(val chat: ChatSession? = null, val lines: List<ChatMessage> = emptyList())

/**
 * The chats looked at last, so one opens at once, and comes back as it was left
 * after the phone has killed the app, while the server is asked in the background.
 *
 * Never the record: whatever the server answers replaces it. Sealed on the way to
 * disk, because this is what somebody said, and emptied on signing out.
 */
class ChatCache(
    private val folder: File,
    private val server: String,
    private val seal: (ByteArray) -> ByteArray,
    private val open: (ByteArray) -> ByteArray,
) {
    private fun fileOf(chatId: String) =
        File(folder, "${server.hashCode().toUInt()}-${chatId.filter(Char::isLetterOrDigit)}")

    /** Null where there is none, or none that can still be read: then it is simply fetched. */
    fun read(chatId: String): CachedChat? = runCatching {
        val text = String(open(fileOf(chatId).readBytes()))
        OrknuxJson.decodeFromString(CachedChat.serializer(), text)
    }.getOrNull()

    fun write(chatId: String, chat: CachedChat) {
        runCatching {
            folder.mkdirs()
            val file = fileOf(chatId)
            val partial = File(folder, "${file.name}.partial")
            partial.writeBytes(seal(OrknuxJson.encodeToString(CachedChat.serializer(), chat).toByteArray()))
            partial.renameTo(file)
            file.setLastModified(System.currentTimeMillis())
            folder.listFiles().orEmpty().sortedByDescending(File::lastModified).drop(MOST_KEPT).forEach(File::delete)
        }
    }

    fun remove(chatId: String) {
        fileOf(chatId).delete()
    }

    private companion object {
        const val MOST_KEPT = 20
    }
}
