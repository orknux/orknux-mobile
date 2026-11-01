package io.mszymanski.orknux.mobile.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.mszymanski.orknux.mobile.design.Banner
import io.mszymanski.orknux.mobile.design.ConfirmDialog
import io.mszymanski.orknux.mobile.design.InitialsTile
import io.mszymanski.orknux.mobile.design.MonoLabel
import io.mszymanski.orknux.mobile.design.OrkField
import io.mszymanski.orknux.mobile.design.OrkIcons
import io.mszymanski.orknux.mobile.design.Ork
import io.mszymanski.orknux.mobile.design.OrkIconButton
import io.mszymanski.orknux.mobile.design.OrknuxLockup
import io.mszymanski.orknux.mobile.design.PrimaryButton
import io.mszymanski.orknux.mobile.design.SecondaryButton
import io.mszymanski.orknux.mobile.design.SectionLabel
import io.mszymanski.orknux.mobile.design.TextDialog
import io.mszymanski.orknux.mobile.design.UserAvatar
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ChatListScreen(
    model: ChatListViewModel,
    userName: String?,
    onOpen: (workspaceId: String, chatId: String) -> Unit,
    onNew: (workspaceId: String) -> Unit,
    onOptions: () -> Unit,
    onSignOut: () -> Unit,
) {
    val state by model.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var acting by remember { mutableStateOf<ChatSession?>(null) }
    var renaming by remember { mutableStateOf<ChatSession?>(null) }
    var deleting by remember { mutableStateOf<ChatSession?>(null) }
    var signingOut by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(Unit) {
        model.refresh()
        val looking = scope.launch { model.keepFresh() }
        onPauseOrDispose { looking.cancel() }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrknuxLockup()
            Spacer(Modifier.weight(1f))
            UserMenu(userName, onOptions) { signingOut = true }
        }
        HorizontalDivider(color = Ork.border)

        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkspacePicker(state.workspace, state.workspaces, model::chooseWorkspace)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("User chats", Modifier.weight(1f))
                PrimaryButton("+ New", { state.workspace?.let { onNew(it.id) } }, enabled = state.workspace != null, compact = true)
            }
            OrkField(query, { query = it }, "Search chats…", leading = OrkIcons.Search)
            state.error?.let { Banner(it) }
        }

        PullToRefreshBox(state.refreshing, model::refresh, Modifier.weight(1f)) {
            val chats = state.chats
            val found = chats?.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }.orEmpty()
            when {
                chats == null && state.error == null -> Centred("Loading…")
                state.noWorkspaces -> Centred("You are not in a workspace yet.")
                chats != null && chats.isEmpty() -> Centred("No chats yet. Start one with + New.")
                chats != null && found.isEmpty() -> Centred("No chat is called that.")
                else -> ChatRows(found, onOpen = { onOpen(it.workspaceId, it.id) }, onAct = { acting = it })
            }
        }
    }

    acting?.let { chat ->
        ChatActions(
            chat,
            onDismiss = { acting = null },
            onPin = { model.setPinned(chat, !chat.pinned); acting = null },
            onRename = { renaming = chat; acting = null },
            onDelete = { deleting = chat; acting = null },
        )
    }
    renaming?.let { chat ->
        TextDialog("Rename chat", chat.title, "Rename", { model.rename(chat, it); renaming = null }, { renaming = null })
    }
    deleting?.let { chat ->
        ConfirmDialog(
            "Delete this chat?",
            "\"${chat.title}\" and everything said in it goes for good.",
            "Delete",
            onConfirm = { model.delete(chat); deleting = null },
            onDismiss = { deleting = null },
        )
    }
    if (signingOut) {
        ConfirmDialog(
            "Sign out?",
            "Your chats stay on the server.",
            "Sign out",
            danger = false,
            onConfirm = { signingOut = false; onSignOut() },
            onDismiss = { signingOut = false },
        )
    }
}

@Composable
private fun Centred(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        LazyColumn { item { Text(text, style = MaterialTheme.typography.bodyMedium, color = Ork.textSecondary, textAlign = TextAlign.Center) } }
    }
}

@Composable
private fun ChatRows(chats: List<ChatSession>, onOpen: (ChatSession) -> Unit, onAct: (ChatSession) -> Unit) {
    val pinned = chats.filter { it.pinned }
    val recent = chats.filterNot { it.pinned }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 32.dp)) {
        if (pinned.isNotEmpty()) {
            item { SectionLabel("Pinned", Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp)) }
            items(pinned, key = { it.id }) { ChatRow(it, onOpen, onAct) }
        }
        if (recent.isNotEmpty()) {
            item { SectionLabel("Recent", Modifier.padding(start = 8.dp, top = if (pinned.isEmpty()) 4.dp else 16.dp, bottom = 8.dp)) }
            items(recent, key = { it.id }) { ChatRow(it, onOpen, onAct) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(chat: ChatSession, onOpen: (ChatSession) -> Unit, onAct: (ChatSession) -> Unit) {
    val unread = chat.unreadCount > 0
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ork.radius8))
            .combinedClickable(onClick = { onOpen(chat) }, onLongClick = { onAct(chat) })
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                chat.title.ifBlank { "New chat" },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal),
                color = if (unread) Ork.textPrimary else Ork.textBody,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val by = listOfNotNull(chat.answeredBy, whenLine(chat.lastMessageAt ?: chat.createdAt)).joinToString(" · ")
            if (by.isNotEmpty()) {
                Text(by, style = MaterialTheme.typography.bodySmall, color = Ork.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (unread) UnreadPill(chat.unreadCount)
    }
}

@Composable
private fun UnreadPill(count: Int) {
    Box(
        Modifier.size(22.dp).background(Ork.unreadBg, RoundedCornerShape(99.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 9) "9+" else count.toString(), style = MaterialTheme.typography.labelSmall, color = Ork.unreadFg)
    }
}

@Composable
private fun WorkspacePicker(current: Workspace?, all: List<Workspace>, onChoose: (Workspace) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Ork.radius6)).background(Ork.surfaceRaised)
                .border(1.dp, Ork.border, RoundedCornerShape(Ork.radius6))
                .clickable(enabled = all.size > 1) { open = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InitialsTile(current?.name.orEmpty())
            Text(current?.name ?: "…", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (all.size > 1) Icon(OrkIcons.ChevronDown, null, Modifier.size(18.dp), tint = Ork.textSecondary)
        }
        DropdownMenu(open, { open = false }, containerColor = Ork.surfaceRaised) {
            all.forEach { workspace ->
                DropdownMenuItem(
                    text = { Text(workspace.name) },
                    leadingIcon = { InitialsTile(workspace.name) },
                    onClick = { open = false; onChoose(workspace) },
                )
            }
        }
    }
}

@Composable
private fun UserMenu(userName: String?, onOptions: () -> Unit, onSignOut: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(Ork.radius8)).clickable { open = true }.padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (userName != null) Text(userName, style = MaterialTheme.typography.labelMedium, color = Ork.textSecondary, maxLines = 1)
            UserAvatar(userName)
        }
        DropdownMenu(open, { open = false }, containerColor = Ork.surfaceRaised) {
            DropdownMenuItem(text = { Text("Options") }, onClick = { open = false; onOptions() })
            DropdownMenuItem(
                text = { Text("Sign out") },
                leadingIcon = { Icon(OrkIcons.LogOut, null, Modifier.size(18.dp), tint = Ork.textSecondary) },
                onClick = { open = false; onSignOut() },
            )
        }
    }
}

@Composable
private fun ChatActions(chat: ChatSession, onDismiss: () -> Unit, onPin: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ork.surface,
        title = { Text(chat.title.ifBlank { "New chat" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(if (chat.pinned) "Unpin" else "Pin", onPin, Modifier.fillMaxWidth())
                SecondaryButton("Rename", onRename, Modifier.fillMaxWidth())
                SecondaryButton("Delete", onDelete, Modifier.fillMaxWidth(), color = Ork.danger)
            }
        },
        confirmButton = {},
    )
}

private val TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm")

/** An ISO-8601 instant in this device's zone, or null for one that is not. */
fun whenLine(iso: String?): String? =
    iso?.let { runCatching { TIME.format(Instant.parse(it).atZone(ZoneId.systemDefault())) }.getOrNull() }
