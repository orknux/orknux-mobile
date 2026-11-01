package io.mszymanski.orknux.mobile.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import io.mszymanski.orknux.mobile.design.AvatarIcon
import io.mszymanski.orknux.mobile.design.GeistSans
import io.mszymanski.orknux.mobile.design.JetBrainsMono
import io.mszymanski.orknux.mobile.design.MonoLabel
import io.mszymanski.orknux.mobile.design.Ork
import io.mszymanski.orknux.mobile.design.OrkField
import io.mszymanski.orknux.mobile.design.OrkIcons
import io.mszymanski.orknux.mobile.design.PrimaryButton
import io.mszymanski.orknux.mobile.design.Tag

/** Opens links in the phone's browser, and only web links: what a model writes is untrusted. */
@Composable
private fun rememberSafeUriHandler(): UriHandler {
    val context = LocalContext.current
    return remember(context) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val scheme = uri.substringBefore(':').lowercase()
                if (scheme != "https" && scheme != "http") return
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
                }
            }
        }
    }
}

/** What a model writes is rendered markdown, not text; see [MarkdownPrep] for what is taken out first. */
@Composable
fun MarkdownText(markdown: String, base: String, modifier: Modifier = Modifier) {
    val body = TextStyle(fontFamily = GeistSans, fontSize = 15.sp, lineHeight = 23.sp, color = Ork.textBody)
    val code = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, lineHeight = 19.sp, color = Ork.textBody)
    val prepared = remember(markdown, base) { MarkdownPrep.prepare(markdown, base) }
    CompositionLocalProvider(LocalUriHandler provides rememberSafeUriHandler()) {
        Markdown(
            prepared,
            modifier = modifier,
            colors = markdownColor(
                text = Ork.textBody,
                codeBackground = Ork.surfaceRaised,
                inlineCodeBackground = Ork.surfaceRaised,
                dividerColor = Ork.border,
                tableBackground = Ork.surface,
            ),
            typography = markdownTypography(
                h1 = body.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = Ork.textPrimary),
                h2 = body.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = Ork.textPrimary),
                h3 = body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ork.textPrimary),
                h4 = body.copy(fontWeight = FontWeight.SemiBold, color = Ork.textPrimary),
                h5 = body.copy(fontWeight = FontWeight.SemiBold, color = Ork.textPrimary),
                h6 = body.copy(fontWeight = FontWeight.Medium, color = Ork.textSecondary),
                text = body,
                paragraph = body,
                ordered = body,
                bullet = body,
                list = body,
                code = code,
                inlineCode = code,
                quote = body.copy(color = Ork.textSecondary),
            ),
            padding = markdownPadding(block = 6.dp, list = 4.dp),
            imageTransformer = Coil3ImageTransformerImpl,
        )
    }
}

/** What the person said: the solid blue bubble of the web client, at the right. */
@Composable
fun UserBubble(message: ChatMessage, attachmentUrl: (String) -> String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AttachmentRow(message.attachments, attachmentUrl)
        if (message.content.isNotBlank()) {
            SelectionContainer {
                Text(
                    message.content,
                    Modifier.widthIn(max = 320.dp)
                        .background(Ork.accent, RoundedCornerShape(topStart = 12.dp, topEnd = 4.dp, bottomStart = 12.dp, bottomEnd = 12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
                    color = Ork.onAccent,
                )
            }
        }
    }
}

@Composable
fun AnswerHeader(name: String?, at: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AvatarIcon(OrkIcons.Cpu)
        Column {
            Text(name ?: "Assistant", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = Ork.textPrimary)
            whenLine(at)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ork.textMuted) }
        }
    }
}

@Composable
fun AttachmentRow(attachments: List<Attachment>, attachmentUrl: (String) -> String) {
    if (attachments.isEmpty()) return
    val uri = rememberSafeUriHandler()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { file ->
            if (file.isPicture) {
                AsyncImage(
                    model = attachmentUrl(file.id),
                    contentDescription = file.filename,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.widthIn(max = 280.dp).heightIn(max = 280.dp).clip(RoundedCornerShape(Ork.radius8)),
                )
            } else {
                Row(
                    Modifier.clip(RoundedCornerShape(Ork.radius6)).background(Ork.surfaceRaised)
                        .border(1.dp, Ork.border, RoundedCornerShape(Ork.radius6))
                        .clickable { uri.openUri(attachmentUrl(file.id)) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(OrkIcons.File, null, Modifier.size(14.dp), tint = Ork.textSecondary)
                    Text(file.filename.ifBlank { "File" }, style = MaterialTheme.typography.bodySmall, color = Ork.textBody, maxLines = 1)
                }
            }
        }
    }
}

/** A collapsed line with a coloured edge, the way the web transcript draws what is not the answer. */
@Composable
private fun Disclosure(
    edge: Color,
    surface: Color,
    fill: Boolean,
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(Ork.radius6)
    Column(
        (if (fill) Modifier.fillMaxWidth() else Modifier).clip(shape).background(surface).border(1.dp, Ork.border, shape)
            .clickable { open = !open }.animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 3.dp, height = 32.dp).background(edge))
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (open) "▾" else "▸", style = MaterialTheme.typography.bodySmall, color = Ork.textMuted)
                header()
            }
        }
        if (open) Box(Modifier.padding(start = 12.dp, end = 10.dp, bottom = 10.dp)) { content() }
    }
}

@Composable
fun ToolLine(tool: String, arguments: String, result: String? = null, failed: Boolean = false, running: Boolean = false) {
    Disclosure(
        edge = if (failed) Ork.danger else Ork.cyan,
        surface = Ork.surface,
        fill = false,
        header = {
            Tag("TOOL", Ork.cyan, Ork.cyanSoft)
            Text(tool, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp), color = Ork.textBody, maxLines = 1)
            if (running) CircularProgressIndicator(Modifier.size(12.dp), color = Ork.cyan, strokeWidth = 1.5.dp)
            if (failed) Tag("FAILED", Ork.danger, Ork.dangerSoft)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (arguments.isNotBlank()) Mono(arguments)
            result?.let { Mono(it.take(MOST_SHOWN)) }
        }
    }
}

@Composable
private fun Mono(text: String) {
    SelectionContainer {
        Text(text, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 17.sp), color = Ork.textSecondary)
    }
}

@Composable
fun ThinkingLine(text: String, millis: Long? = null, running: Boolean = false) {
    Disclosure(
        edge = Ork.thinking,
        surface = Ork.thinkingSurface,
        fill = true,
        header = {
            Tag("THINKING", Ork.thinking, Ork.thinkingSoft)
            if (millis != null && millis > 0) Text(thinkingTime(millis), style = MaterialTheme.typography.bodySmall, color = Ork.textMuted)
            if (running) CircularProgressIndicator(Modifier.size(12.dp), color = Ork.thinking, strokeWidth = 1.5.dp)
        },
    ) {
        SelectionContainer {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Ork.textSecondary)
        }
    }
}

@Composable
fun NoteLine(text: String) {
    Disclosure(edge = Ork.textMuted, surface = Ork.surface, fill = true, header = { Tag("NOTE", Ork.textSecondary, Ork.surfaceHover) }) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ork.textSecondary)
    }
}

/** While nothing has come yet: the words are what somebody watching a silence wanted. */
@Composable
fun WaitingLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(14.dp), color = Ork.accent, strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = Ork.textMuted)
    }
}

/**
 * A question from the agent, and the turn may be waiting on it. Drawn from its
 * options alone, as the web's card is: the plan's Approve, Keep planning, Reject.
 */
@Composable
fun InteractionCard(request: InteractionRequest, base: String, onAnswer: (List<String>, String?) -> Unit) {
    val chosen = remember(request.id) { mutableStateListOf<String>() }
    var words by rememberSaveable(request.id) { mutableStateOf("") }
    val shape = RoundedCornerShape(Ork.radius8)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Ork.surface).border(1.dp, Ork.accent, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Tag(if (request.isPlan) "PLAN" else (request.askedBy?.uppercase() ?: "QUESTION"), Ork.textSecondary, Ork.surfaceHover)
        MarkdownText(request.question, base)
        request.items.forEach { item ->
            Column {
                Text(item.name, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp), color = Ork.textBody)
                item.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ork.textMuted) }
            }
        }
        request.options.forEachIndexed { index, option ->
            val on = option.id in chosen
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Ork.radius6)).background(Ork.surfaceRaised)
                    .border(1.dp, if (on) Ork.accent else Ork.border, RoundedCornerShape(Ork.radius6))
                    .clickable {
                        if (request.multiple) {
                            if (on) chosen.remove(option.id) else chosen.add(option.id)
                        } else {
                            onAnswer(listOf(option.id), null)
                        }
                    }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (request.multiple) {
                    Checkbox(on, null, Modifier.size(18.dp), colors = CheckboxDefaults.colors(checkedColor = Ork.accent))
                } else {
                    Text("${index + 1}.", style = MaterialTheme.typography.labelMedium, color = Ork.textMuted)
                }
                Column {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = if (option.style == "danger") Ork.danger else Ork.textPrimary,
                    )
                    option.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ork.textMuted) }
                }
            }
        }
        if (request.allowsText || request.multiple) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (request.allowsText) {
                    OrkField(words, { words = it }, "Or answer in your own words", Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                PrimaryButton(
                    "Send",
                    { onAnswer(chosen.toList(), words) },
                    enabled = words.isNotBlank() || chosen.isNotEmpty(),
                )
            }
        }
    }
}

private const val MOST_SHOWN = 4_000
