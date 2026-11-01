package io.mszymanski.orknux.mobile.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.launch
import io.mszymanski.orknux.mobile.design.Banner
import io.mszymanski.orknux.mobile.design.ConfirmDialog
import io.mszymanski.orknux.mobile.design.JetBrainsMono
import io.mszymanski.orknux.mobile.design.Ork
import io.mszymanski.orknux.mobile.design.OrkIconButton
import io.mszymanski.orknux.mobile.design.OrkIcons
import io.mszymanski.orknux.mobile.design.PrimaryButton
import io.mszymanski.orknux.mobile.design.TextDialog
import io.mszymanski.orknux.mobile.voice.AndroidClipPlayer
import io.mszymanski.orknux.mobile.voice.AndroidMicrophone
import io.mszymanski.orknux.mobile.voice.AndroidThinkingSound
import io.mszymanski.orknux.mobile.voice.DeviceSpeech
import io.mszymanski.orknux.mobile.voice.DeviceTranscriber
import io.mszymanski.orknux.mobile.voice.Dictation
import io.mszymanski.orknux.mobile.voice.Reading
import io.mszymanski.orknux.mobile.voice.SpeechApi
import io.mszymanski.orknux.mobile.voice.SpeechChunking
import io.mszymanski.orknux.mobile.voice.SpeechEngine
import io.mszymanski.orknux.mobile.voice.VoiceConversation
import io.mszymanski.orknux.mobile.voice.VoicePanel
import io.mszymanski.orknux.mobile.voice.VoiceRoute
import io.mszymanski.orknux.mobile.voice.VoiceSession
import io.mszymanski.orknux.mobile.voice.VoiceTuning
import io.mszymanski.orknux.mobile.voice.VoiceUi
import io.mszymanski.orknux.mobile.voice.Waveform

private val COMPOSER_BUTTON = 44.dp
private val PICKER_HEIGHT = 38.dp
/** Within this of the newest still counts as being there: a thumb never lands exactly on the end. */
private const val AT_NEWEST_PX = 48

/** One thing the transcript draws, newest last. */
private sealed interface TranscriptRow {
    val key: String

    data class Line(override val key: String, val message: ChatMessage, val read: Boolean) : TranscriptRow
    data class Step(override val key: String, val step: LiveStep) : TranscriptRow
    data class LiveThinking(override val key: String, val text: String) : TranscriptRow
    data class LiveAnswer(override val key: String, val text: String) : TranscriptRow
    data class Status(override val key: String, val text: String) : TranscriptRow
    data class Files(override val key: String, val files: List<Attachment>) : TranscriptRow
    data class Spend(override val key: String, val text: String) : TranscriptRow
    data class Actions(override val key: String, val message: ChatMessage, val last: Boolean) : TranscriptRow
    data class Replies(override val key: String, val replies: List<String>) : TranscriptRow
    data class Question(override val key: String, val request: InteractionRequest) : TranscriptRow
}

private fun rowsOf(state: ConversationState): List<TranscriptRow> {
    val rows = mutableListOf<TranscriptRow>()
    val lastAnswer = state.lines.indexOfLast { it.isAnswer }
    val readAt = readLineIndex(state.lines, state.live)
    state.lines.forEachIndexed { index, message ->
        rows += TranscriptRow.Line("l$index", message, read = index == readAt)
        if (message.isAnswer && message.content.isNotBlank()) {
            rows += TranscriptRow.Actions("a$index", message, last = index == lastAnswer && state.live == null)
        }
    }
    state.live?.let { live ->
        live.steps.forEachIndexed { index, step -> rows += TranscriptRow.Step("s$index", step) }
        if (live.thinking.isNotBlank()) rows += TranscriptRow.LiveThinking("think", live.thinking)
        if (live.answer.isNotEmpty()) rows += TranscriptRow.LiveAnswer("answer", live.answer)
        if (live.files.isNotEmpty()) rows += TranscriptRow.Files("files", live.files)
        if (live.answer.isEmpty()) rows += TranscriptRow.Status("status", live.status ?: "Waiting for ${state.answeredBy ?: "the answer"}…")
    }
    if (state.live == null) state.lastTurn?.let { rows += TranscriptRow.Spend("spend", it) }
    state.openRequests.forEach { rows += TranscriptRow.Question("q${it.id}", it) }
    if (state.live == null && state.suggestions.isNotEmpty()) rows += TranscriptRow.Replies("replies", state.suggestions)
    return rows
}

@Composable
fun ConversationScreen(
    model: ConversationViewModel,
    base: String,
    workspaceId: String,
    client: io.mszymanski.orknux.mobile.core.OrknuxClient,
    engineOf: () -> SpeechEngine,
    listenOf: () -> SpeechEngine,
    thinkingPauseOf: () -> Int,
    thinkingPitchOf: () -> Int,
    speedOf: () -> Float,
    onSpeedChosen: (Float) -> Unit,
    onBack: () -> Unit,
) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf("") }
    val speech = remember(client, workspaceId) { SpeechApi(client, workspaceId) }
    val device = remember(context) { DeviceSpeech(context) }
    val ears = remember(context) { DeviceTranscriber(context) }
    // The phone stands in where the workspace has no model, as well as where it was chosen.
    val workspaceLoaded = state.workspace
    val listening = when {
        listenOf() == SpeechEngine.DEVICE -> SpeechEngine.DEVICE
        workspaceLoaded != null && !workspaceLoaded.canListen && DeviceTranscriber.isAvailable(context) -> SpeechEngine.DEVICE
        else -> SpeechEngine.SERVER
    }
    val canListen = listening == SpeechEngine.DEVICE || workspaceLoaded?.canListen == true
    val transcribe: suspend (ByteArray) -> String = { wav ->
        if (listening == SpeechEngine.DEVICE) ears.transcribe(wav) else speech.transcribe(wav)
    }
    val engine = if (workspaceLoaded != null && !workspaceLoaded.canSpeak) SpeechEngine.DEVICE else engineOf()
    val canSpeak = engine == SpeechEngine.DEVICE || workspaceLoaded?.canSpeak == true
    val speak: suspend (String) -> ByteArray = { text ->
        if (engine == SpeechEngine.DEVICE) device.speak(text) else speech.speak(text)
    }
    // A workspace's cuts are about its speech model; the phone's voice starts at once on any length up to its limit.
    fun chunking() = if (engine == SpeechEngine.DEVICE) SpeechChunking.SENTENCE else SpeechChunking.of(state.workspace?.voiceSpeechChunking)

    var voice by remember { mutableStateOf<VoiceSession?>(null) }
    val route = remember(context) { VoiceRoute(context) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun openVoice() {
        route.enter()
        val session = VoiceSession(
            scope = scope,
            microphone = AndroidMicrophone(),
            tuning = VoiceTuning.of(state.workspace),
            chunking = chunking(),
            conversation = object : VoiceConversation {
                override fun say(text: String) = model.sayWhenFree(text)
                override fun interrupt() = model.stop()
            },
            transcribe = transcribe,
            speak = speak,
            fetchSound = speech::fetch,
            player = AndroidClipPlayer(context, conversation = true),
            initialSpeed = speedOf(),
            onSpeedChosen = onSpeedChosen,
            thinkingSound = AndroidThinkingSound(thinkingPauseOf(), thinkingPitchOf()),
        )
        model.listener = object : TurnListener {
            override fun onTurnBegan() = session.onTurnBegan()
            override fun onTurnEvent(event: TurnEvent) = session.onTurnEvent(event)
            override fun onTurnOver() = session.onTurnOver()
        }
        session.start()
        voice = session
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun closeVoice() {
        val open = voice ?: return
        open.close()
        voice = null
        model.listener = null
        route.leave()
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    val askForVoice = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openVoice() else problem = "Voice needs the microphone. Allow it in the phone's settings."
    }

    // Dictation outlives a drawing of this screen, and the engine is only settled once the workspace has loaded.
    val latestTranscribe by rememberUpdatedState(transcribe)
    val dictation = remember(client, workspaceId) {
        Dictation(
            scope,
            AndroidMicrophone(),
            VoiceTuning.of(null),
            { wav -> latestTranscribe(wav) },
            onText = { heard -> draft = if (draft.isBlank()) heard else "$draft $heard" },
            onTrouble = { problem = it },
        )
    }
    val dictationState by dictation.state.collectAsState()
    val askForDictation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) dictation.press() else problem = "Dictation needs the microphone. Allow it in the phone's settings."
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri ->
            val resolver = context.contentResolver
            model.attach(displayName(context, uri), resolver.getType(uri) ?: "application/octet-stream") {
                resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("That file could not be read.")
            }
        }
    }

    // Read aloud under one answer; a voice from nowhere once the chat is left, so it stops with the screen.
    var readingKey by remember { mutableStateOf<String?>(null) }
    var readingNow by remember { mutableStateOf<Reading?>(null) }

    fun stopReading() {
        readingNow?.stop()
        readingNow = null
        readingKey = null
    }

    fun readAloud(key: String, text: String) {
        if (readingKey == key) {
            stopReading()
            return
        }
        stopReading()
        val reading = Reading(
            scope = scope,
            chunking = chunking(),
            speak = speak,
            fetchSound = speech::fetch,
            player = AndroidClipPlayer(context, conversation = false),
            speed = speedOf,
            onTrouble = { problem = it },
            onFinished = {
                if (readingKey == key) {
                    readingKey = null
                    readingNow = null
                }
            },
        )
        readingKey = key
        readingNow = reading
        reading.finish(text)
    }

    // The microphone needs a foreground service to run unseen, and a voice talking to an empty room helps nobody.
    LifecycleStartEffect(Unit) {
        model.resume()
        onStopOrDispose {
            model.pause()
            dictation.cancel()
            stopReading()
            closeVoice()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            closeVoice()
            device.close()
        }
    }
    BackHandler(voice != null) { closeVoice() }

    LaunchedEffect(state.returnedDraft) {
        state.returnedDraft?.let {
            draft = it
            model.draftReturned()
        }
    }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val opener = remember(client) { AttachmentOpener(context, client, scope) { problem = it } }
    CompositionLocalProvider(LocalAttachmentOpener provides opener) {
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        ConversationBar(
            state,
            onBack = onBack,
            onChooseAgent = model::chooseAgent,
            onRename = { renaming = true },
            onPin = { model.setPinned(!(state.chat?.pinned ?: false)) },
            onDelete = { deleting = true },
        )
        HorizontalDivider(color = Ork.border)

        voice?.let { session ->
            val ui by session.ui.collectAsState(VoiceUi())
            VoicePanel(
                ui,
                onTap = session::tap,
                onMute = session::toggleMute,
                onSpeed = session::setSpeed,
                onCancelWaiting = session::cancelWaiting,
                onClose = { closeVoice() },
            )
            HorizontalDivider(color = Ork.border)
        }

        val clipboard = LocalClipboardManager.current
        Transcript(
            state,
            base,
            Modifier.weight(1f).background(Ork.readingVeil),
            attachmentUrl = model::attachmentUrl,
            readingKey = readingKey,
            onCopy = { clipboard.setText(AnnotatedString(it)) },
            onRead = ::readAloud,
            canRead = canSpeak,
            onRegenerate = model::regenerate,
            onReply = { model.send(it) },
            onAnswer = model::answer,
        )

        (state.error ?: problem)?.let { message ->
            Banner(
                message,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).clickable {
                    model.dismissError()
                    problem = null
                },
            )
        }

        Composer(
            draft = draft,
            onDraft = { draft = it },
            state = state,
            canSpeak = canSpeak,
            canListen = canListen,
            dictating = dictationState,
            voiceOpen = voice != null,
            onSend = {
                model.send(draft, voice = voice != null)
                draft = ""
            },
            onStop = model::stop,
            onAttach = { pickFile.launch("*/*") },
            onRemovePending = model::removePending,
            onMode = model::setMode,
            onEffort = model::setEffort,
            onDictate = {
                if (hasMicrophone(context)) dictation.press() else askForDictation.launch(Manifest.permission.RECORD_AUDIO)
            },
            onVoice = {
                when {
                    voice != null -> closeVoice()
                    hasMicrophone(context) -> openVoice()
                    else -> askForVoice.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onUnavailable = { problem = it },
        )
    }
    }

    if (renaming) {
        TextDialog("Rename chat", state.chat?.title.orEmpty(), "Rename", { model.rename(it); renaming = false }, { renaming = false })
    }
    if (deleting) {
        ConfirmDialog(
            "Delete this chat?",
            "It and everything said in it goes for good.",
            "Delete",
            onConfirm = { model.delete(); deleting = false },
            onDismiss = { deleting = false },
        )
    }
}

private fun hasMicrophone(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun displayName(context: android.content.Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "file"

@Composable
private fun ConversationBar(
    state: ConversationState,
    onBack: () -> Unit,
    onChooseAgent: (Agent) -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var agents by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(Ork.surface).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OrkIconButton(OrkIcons.ArrowLeft, "Back", onBack)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                state.chat?.title?.ifBlank { "New chat" } ?: "New chat",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(Ork.radius4)).clickable(enabled = state.agents.isNotEmpty()) { agents = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        state.answeredBy ?: "Default agent",
                        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp),
                        color = Ork.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.agents.isNotEmpty()) Icon(OrkIcons.ChevronDown, null, Modifier.size(14.dp), tint = Ork.textMuted)
                }
                DropdownMenu(agents, { agents = false }, containerColor = Ork.surfaceRaised) {
                    state.agents.forEach { agent ->
                        DropdownMenuItem(text = { Text(agent.name) }, onClick = { agents = false; onChooseAgent(agent) })
                    }
                }
            }
        }
        if (state.chat != null) {
            Box {
                Text(
                    "⋮",
                    Modifier.clip(CircleShape).clickable { menu = true }.semantics { contentDescription = "Chat options" }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = Ork.textSecondary,
                )
                DropdownMenu(menu, { menu = false }, containerColor = Ork.surfaceRaised) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(if (state.chat.pinned) "Unpin" else "Pin") }, onClick = { menu = false; onPin() })
                    DropdownMenuItem(text = { Text("Delete", color = Ork.danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun Transcript(
    state: ConversationState,
    base: String,
    modifier: Modifier,
    attachmentUrl: (String) -> String,
    readingKey: String?,
    onCopy: (String) -> Unit,
    onRead: (String, String) -> Unit,
    canRead: Boolean,
    onRegenerate: () -> Unit,
    onReply: (String) -> Unit,
    onAnswer: (InteractionRequest, List<String>, String?) -> Unit,
) {
    val rows = remember(state) { rowsOf(state).asReversed() }
    if (rows.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                if (state.loading) "Loading…" else "Say something to ${state.answeredBy ?: "start"}.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ork.textSecondary,
            )
        }
        return
    }
    // Reversed, so a growing answer stays pinned above the composer and history scrolls up.
    val list = rememberLazyListState()
    // Follows what arrives only while the newest is in view: scrolled up to read, the list stays put and says so.
    var following by remember { mutableStateOf(true) }
    var somethingNew by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { Triple(list.isScrollInProgress, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
            .collect { (scrolling, first, offset) ->
                val atNewest = first == 0 && offset <= AT_NEWEST_PX
                if (scrolling) following = atNewest
                if (atNewest) somethingNew = false
            }
    }
    val scope = rememberCoroutineScope()
    // Somebody who has just said something wants to see what comes of it, wherever they had scrolled to.
    val said = state.lines.count { it.isUser }
    LaunchedEffect(said) {
        following = true
        somethingNew = false
    }
    val arrived = rows.size to (state.live?.answer?.length ?: 0)
    LaunchedEffect(arrived, said) {
        if (following) list.scrollToItem(0) else somethingNew = true
    }
    Box(modifier.fillMaxWidth()) {
    LazyColumn(
        Modifier.fillMaxSize(),
        state = list,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is TranscriptRow.Line -> HistoryLine(row.message, row.read, state, base, attachmentUrl)
                is TranscriptRow.Step -> LiveStepView(row.step, base)
                is TranscriptRow.LiveThinking -> ThinkingLine(row.text, running = true)
                is TranscriptRow.LiveAnswer -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnswerHeader(state.answeredBy, null)
                    MarkdownText(row.text, base)
                }
                is TranscriptRow.Status -> WaitingLine(row.text)
                is TranscriptRow.Files -> AttachmentRow(row.files, attachmentUrl)
                is TranscriptRow.Spend -> Text(row.text, style = MaterialTheme.typography.bodySmall, color = Ork.textMuted)
                is TranscriptRow.Actions -> Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    OrkIconButton(OrkIcons.Copy, "Copy", { onCopy(row.message.content) }, Modifier.size(34.dp), iconSize = 15.dp, tint = Ork.textMuted)
                    if (canRead) {
                        OrkIconButton(
                            OrkIcons.Volume,
                            "Read aloud",
                            { onRead(row.key, row.message.content) },
                            Modifier.size(34.dp),
                            iconSize = 15.dp,
                            tint = if (readingKey == row.key) Ork.accent else Ork.textMuted,
                        )
                    }
                    if (row.last) {
                        OrkIconButton(OrkIcons.Refresh, "Ask again", onRegenerate, Modifier.size(34.dp), iconSize = 15.dp, tint = Ork.textMuted)
                    }
                }
                is TranscriptRow.Replies -> Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.replies.forEach { reply ->
                        Text(
                            reply,
                            Modifier.clip(RoundedCornerShape(99.dp)).border(1.dp, Ork.border, RoundedCornerShape(99.dp))
                                .clickable { onReply(reply) }.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = Ork.textBody,
                            maxLines = 1,
                        )
                    }
                }
                is TranscriptRow.Question -> InteractionCard(row.request, base) { options, text -> onAnswer(row.request, options, text) }
            }
        }
    }
    if (somethingNew) {
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                .clip(RoundedCornerShape(99.dp)).background(Ork.accent)
                .clickable {
                    following = true
                    somethingNew = false
                    scope.launch { list.scrollToItem(0) }
                }.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(OrkIcons.ArrowLeft, null, Modifier.size(16.dp).rotate(-90f), tint = Ork.onAccent)
            Text("New message", style = MaterialTheme.typography.labelMedium, color = Ork.onAccent)
        }
    }
    }
}

@Composable
private fun HistoryLine(message: ChatMessage, read: Boolean, state: ConversationState, base: String, attachmentUrl: (String) -> String) {
    when {
        message.isUser -> UserBubble(message, attachmentUrl, read = true.takeIf { read })
        message.isCall -> ToolLine(message.actor ?: "tool", message.content)
        message.isThought -> ThinkingLine(message.thinking ?: message.content, message.thinkingMillis?.toLong())
        message.isAnswer -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AnswerHeader(message.actor ?: state.answeredBy, message.at)
            if (!message.thinking.isNullOrBlank()) ThinkingLine(message.thinking, message.thinkingMillis?.toLong())
            if (message.content.isNotBlank()) MarkdownText(message.content, base)
            AttachmentRow(message.attachments, attachmentUrl)
        }
        message.content.isNotBlank() -> NoteLine(message.content)
    }
}

@Composable
private fun LiveStepView(step: LiveStep, base: String) {
    when (step) {
        is LiveStep.Call -> ToolLine(step.tool, step.arguments, step.result, step.failed, running = step.result == null)
        is LiveStep.Said -> MarkdownText(step.text, base)
        is LiveStep.Thought -> ThinkingLine(step.text, step.millis)
        is LiveStep.Person -> UserBubble(ChatMessage("user", step.text), attachmentUrl = { "" }, read = step.read)
        is LiveStep.Picture -> MarkdownText(step.markdown, base)
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    state: ConversationState,
    canSpeak: Boolean,
    canListen: Boolean,
    dictating: Dictation.State,
    voiceOpen: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onRemovePending: (Attachment) -> Unit,
    onMode: (ChatMode) -> Unit,
    onEffort: (String?) -> Unit,
    onDictate: () -> Unit,
    onVoice: () -> Unit,
    onUnavailable: (String) -> Unit,
) {
    val answering = state.live != null
    val canSend = draft.isNotBlank() || (state.pending.isNotEmpty() && !answering)
    val shape = RoundedCornerShape(Ork.radius12)
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(shape).background(Ork.surface).border(1.dp, Ork.border, shape).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.pending.isNotEmpty() || state.uploading) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                state.pending.forEach { file ->
                    Text(
                        "${file.filename}  ×",
                        Modifier.clip(RoundedCornerShape(Ork.radius6)).background(Ork.surfaceRaised).clickable { onRemovePending(file) }
                            .height(PICKER_HEIGHT).padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ork.textBody,
                        maxLines = 1,
                    )
                }
                if (state.uploading) CircularProgressIndicator(Modifier.size(14.dp), color = Ork.accent, strokeWidth = 2.dp)
            }
        }
        val text = MaterialTheme.typography.bodyLarge.copy(color = Ork.textPrimary)
        BasicTextField(
            draft,
            onDraft,
            Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 140.dp).padding(horizontal = 6.dp, vertical = 6.dp),
            textStyle = text,
            cursorBrush = SolidColor(Ork.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) {
                        Text(
                            if (dictating == Dictation.State.RECORDING) "Listening…"
                            else if (dictating == Dictation.State.TRANSCRIBING) "Transcribing…"
                            else if (answering) "Add to what it is working on…"
                            else "Type a message…",
                            style = text,
                            color = Ork.textMuted,
                        )
                    }
                    inner()
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // Takes what the microphones and Send leave, and scrolls rather than pushing them off a narrow phone.
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OrkIconButton(OrkIcons.Plus, "Attach", onAttach, Modifier.size(COMPOSER_BUTTON), iconSize = 22.dp)
                ModePicker(state.mode, onMode)
                state.effort?.takeIf { it.reasoningEfforts.isNotEmpty() }?.let { EffortPicker(it, onEffort) }
            }
            val workspace = state.workspace
            val dictatingNow = dictating != Dictation.State.IDLE
            if (canListen && ((!canSend && !answering) || dictatingNow)) {
                OrkIconButton(
                    OrkIcons.Mic,
                    "Dictate",
                    onDictate,
                    Modifier.size(COMPOSER_BUTTON),
                    iconSize = 22.dp,
                    tint = if (dictating == Dictation.State.IDLE) Ork.textSecondary else Ork.accent,
                )
            }
            // Send takes the microphones' place while there is text: the row has no room for all of them.
            if (workspace != null && !workspace.voiceButtonHidden && (!canSend || voiceOpen)) {
                VoiceButton(voiceOpen) {
                    when {
                        canListen && canSpeak -> onVoice()
                        else -> onUnavailable(
                            "Voice needs a " +
                                listOfNotNull("transcription model".takeUnless { canListen }, "speech model".takeUnless { canSpeak }).joinToString(" and a ") +
                                ". Choose one in the workspace's settings, or use this phone's in Options.",
                        )
                    }
                }
            }
            // Only when there is something to send or stop, so the microphones keep the corner the thumb is on.
            when {
                // Drawn small, as other chat apps draw it; the whole 44dp still takes the tap.
                canSend -> Box(
                    Modifier.size(COMPOSER_BUTTON).clip(CircleShape).clickable(onClick = onSend)
                        .semantics { contentDescription = "Send" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(32.dp).background(Ork.accent, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(OrkIcons.ArrowLeft, null, Modifier.size(18.dp).rotate(90f), tint = Ork.onAccent)
                    }
                }
                answering -> OrkIconButton(OrkIcons.Stop, "Stop", onStop, Modifier.size(COMPOSER_BUTTON), iconSize = 20.dp, tint = Ork.textPrimary)
            }
        }
    }
}

@Composable
private fun VoiceButton(open: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(COMPOSER_BUTTON).clip(CircleShape).background(if (open) Ork.accent else Ork.accentSoft).clickable(onClick = onClick)
            .semantics { contentDescription = if (open) "End voice" else "Voice" },
        contentAlignment = Alignment.Center,
    ) {
        Waveform(Modifier.size(22.dp), if (open) Ork.onAccent else Ork.textSecondary)
    }
}

/** How hard the model thinks, for this chat's next turns; drawn only where its model takes an effort. */
@Composable
private fun EffortPicker(effort: ChatEffort, onEffort: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chosen = effort.reasoningEffort
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(Ork.radius6))
                .border(1.dp, if (chosen == null) Ork.border else Ork.accent, RoundedCornerShape(Ork.radius6))
                .clickable { open = true }.semantics { contentDescription = "Reasoning effort: ${effortLabel(chosen)}" }
                .height(PICKER_HEIGHT).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(OrkIcons.Brain, null, Modifier.size(18.dp), tint = if (chosen == null) Ork.textSecondary else Ork.textPrimary)
            Text(effortLabel(chosen), style = MaterialTheme.typography.labelMedium, color = if (chosen == null) Ork.textSecondary else Ork.textPrimary, maxLines = 1)
        }
        DropdownMenu(open, { open = false }, containerColor = Ork.surfaceRaised) {
            (listOf<String?>(null) + effort.reasoningEfforts).forEach { level ->
                DropdownMenuItem(
                    text = { Text(effortLabel(level), color = if (level == chosen) Ork.accent else Ork.textPrimary) },
                    onClick = {
                        open = false
                        if (level != chosen) onEffort(level)
                    },
                )
            }
        }
    }
}

/** Null is the model's own setting. */
private fun effortLabel(level: String?): String = when (level) {
    null -> "Auto"
    "xhigh" -> "XHigh"
    else -> level.replaceFirstChar(Char::uppercase)
}

@Composable
private fun ModePicker(mode: ChatMode, onMode: (ChatMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(Ork.radius6))
                .border(1.dp, if (mode == ChatMode.PLAN) Ork.accent else Ork.border, RoundedCornerShape(Ork.radius6))
                .clickable { open = true }.height(PICKER_HEIGHT).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(mode.label, style = MaterialTheme.typography.labelMedium, color = if (mode == ChatMode.PLAN) Ork.textPrimary else Ork.textSecondary)
            Icon(OrkIcons.ChevronDown, null, Modifier.size(18.dp), tint = Ork.textMuted)
        }
        DropdownMenu(open, { open = false }, containerColor = Ork.surfaceRaised) {
            ChatMode.OFFERED.forEach { one ->
                DropdownMenuItem(text = { Text(one.label) }, onClick = { open = false; onMode(one) })
            }
        }
    }
}
