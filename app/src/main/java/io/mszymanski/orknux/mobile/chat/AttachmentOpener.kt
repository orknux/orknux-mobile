package io.mszymanski.orknux.mobile.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import io.mszymanski.orknux.mobile.core.OrknuxClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Opens a file from a chat in whatever app on the phone takes it.
 *
 * Fetched here rather than handed to a browser by its address: the browser is not
 * signed in to the server, this client is. It goes to the app's cache, which
 * Android clears when it needs the room.
 */
class AttachmentOpener(
    private val context: Context,
    private val client: OrknuxClient,
    private val scope: CoroutineScope,
    private val onTrouble: (String) -> Unit,
) {
    /** What is being fetched, so its row can say so: a large file takes a moment. */
    val opening = mutableStateOf<String?>(null)

    fun open(file: Attachment) {
        if (opening.value != null) return
        opening.value = file.id
        scope.launch {
            try {
                val local = fetch(file)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", local)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, file.contentType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ActivityNotFoundException) {
                onTrouble("No app on this phone opens ${file.contentType} files.")
            } catch (failure: IOException) {
                onTrouble(failure.message ?: "That file could not be fetched.")
            } finally {
                opening.value = null
            }
        }
    }

    private suspend fun fetch(file: Attachment): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "attachments/${file.id.filter(Char::isLetterOrDigit)}").apply { mkdirs() }
        val target = File(folder, safeFileName(file.filename))
        client.send(client.request("/api/attachments/${file.id}").build()) { response ->
            response.body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        target
    }
}

val LocalAttachmentOpener = compositionLocalOf<AttachmentOpener?> { null }

/** A name a model or a stranger chose, made safe to write: it never leaves the folder it is put in. */
fun safeFileName(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("""[^\p{L}\p{N}._ -]"""), "_").trim()
    return base.trimStart('.').ifEmpty { "file" }.take(120)
}
