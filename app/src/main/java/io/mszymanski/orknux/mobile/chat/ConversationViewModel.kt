package io.mszymanski.orknux.mobile.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.mszymanski.orknux.mobile.core.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface LiveStep {
    data class Call(val at: Int, val tool: String, val arguments: String, val result: String? = null, val failed: Boolean = false) : LiveStep
    data class Said(val text: String) : LiveStep
    data class Thought(val text: String, val millis: Long) : LiveStep
    /** Written during the turn: it waits in the agent's inbox until its next step takes it in. */
    data class Person(val text: String, val read: Boolean = false) : LiveStep
    data class Picture(val markdown: String) : LiveStep
}

/** The turn being written, drawn apart from the history until the history holds it. */
data class LiveTurn(
    val thinking: String = "",
    val steps: List<LiveStep> = emptyList(),
    val answer: String = "",
    val status: String? = null,
)

data class PendingFile(val attachment: Attachment)

data class ConversationState(
    val chat: ChatSession? = null,
    val lines: List<ChatMessage> = emptyList(),
    val loading: Boolean = false,
    val live: LiveTurn? = null,
    val error: String? = null,
    /** Given back to the composer when a message was refused. */
    val returnedDraft: String? = null,
    val lastTurn: String? = null,
    val agents: List<Agent> = emptyList(),
    /** A new chat's choices, held until its first message makes it. */
    val pickedAgent: Agent? = null,
    val pickedMode: ChatMode = ChatMode.AUTO,
    val effort: ChatEffort? = null,
    val requests: List<InteractionRequest> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val pending: List<Attachment> = emptyList(),
    val uploading: Boolean = false,
    val workspace: Workspace? = null,
    val deleted: Boolean = false,
) {
    val mode: ChatMode get() = chat?.mode ?: pickedMode
    val answeredBy: String? get() = chat?.answeredBy ?: pickedAgent?.name
    val openRequests: List<InteractionRequest> get() = requests.filter { it.isOpen }
}

/** Whoever wants to hear a turn as it happens; the voice panel. */
interface TurnListener {
    fun onTurnBegan()
    fun onTurnEvent(event: TurnEvent)
    fun onTurnOver()
}

/**
 * One chat. The server keeps the record; this draws it and follows what is
 * being written. A turn goes on being answered when the screen is left, so
 * opening a chat always asks whether one is running.
 */
class ConversationViewModel(
    private val api: ChatApi,
    private val workspaceId: String,
    chatId: String?,
) : ViewModel() {
    private var id: String? = chatId
    private val _state = MutableStateFlow(ConversationState(loading = chatId != null))
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    var listener: TurnListener? = null

    private var turnJob: Job? = null
    private var followJob: Job? = null
    private var pollJob: Job? = null
    private var startJob: Job? = null
    /** Made by opening this screen, so it is ours to take away if nothing is ever said in it. */
    private var madeHere = false
    private val reader = TurnReader(follow = { onFrame -> api.follow(id!!, onFrame = onFrame) })

    init {
        // Made on opening, as the web's + New does: only a chat can say which efforts its model takes.
        if (chatId == null) startJob = viewModelScope.launch { makeChat() }
        viewModelScope.launch {
            runCatching { api.workspace(workspaceId) }.onSuccess { w -> _state.update { it.copy(workspace = w) } }
        }
        viewModelScope.launch {
            runCatching { api.agents(workspaceId) }.onSuccess { list ->
                _state.update { it.copy(agents = list.filter(Agent::usable)) }
            }
        }
    }

    private suspend fun makeChat() {
        try {
            val agent = _state.value.pickedAgent?.id
            val made = api.start(workspaceId, agent)
            id = made.id
            madeHere = true
            val moded = runCatching { api.setMode(made.id, ChatMode.AUTO) }.getOrDefault(made)
            _state.update { it.copy(chat = moded) }
            refreshEffort()
            startFollowing()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            // Said now, and tried again by the first message.
            fail(failure.message ?: "The chat could not be started.")
        }
    }

    override fun onCleared() {
        val empty = id.takeIf { madeHere && _state.value.lines.isEmpty() && _state.value.live == null } ?: return
        // Outlives this model on purpose: its own scope has just ended.
        CoroutineScope(Dispatchers.IO).launch { runCatching { api.delete(empty) } }
    }

    /** The screen came into view. */
    fun resume() {
        if (id != null && turnJob?.isActive != true) {
            // A followed turn may have ended unseen; if not, the follow draws it again from its first frame.
            _state.update { it.copy(live = null) }
            viewModelScope.launch { reload(markRead = true) }
            startFollowing()
        }
        if (pollJob?.isActive != true) {
            pollJob = viewModelScope.launch {
                while (isActive) {
                    if (_state.value.live != null) refreshRequests()
                    delay(REQUEST_POLL_MS)
                }
            }
        }
    }

    /** The screen left view: an answer being written goes on, and is read back on return. */
    fun pause() {
        followJob?.cancel()
        pollJob?.cancel()
    }

    private suspend fun reload(markRead: Boolean) {
        val chat = id ?: return
        try {
            val session = api.session(chat)
            val lines = api.messages(chat)
            _state.update {
                it.copy(
                    chat = session ?: it.chat,
                    // A turn being drawn already holds what it says; the follow's `kept` trims this.
                    lines = if (it.live == null) lines else it.lines,
                    loading = false,
                )
            }
            if (session == null) fail("That chat is no longer here.")
            refreshEffort()
            refreshRequests()
            if (markRead) runCatching { api.markRead(chat) }
            refreshSuggestions()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            _state.update { it.copy(loading = false) }
            fail(failure.message ?: "The chat could not be read.")
        }
    }

    /** A chat whose effort cannot be read draws no control, as a model taking none does. */
    private suspend fun refreshEffort() {
        val chat = id ?: return
        val found = runCatching { api.effort(chat) }.getOrNull()
        _state.update { it.copy(effort = found) }
    }

    fun setEffort(effort: String?) {
        val chat = id ?: return
        viewModelScope.launch {
            try {
                val fresh = api.setEffort(chat, effort)
                _state.update { it.copy(effort = fresh) }
            } catch (refused: IOException) {
                fail(refused.message ?: "That could not be changed.")
            }
        }
    }

    private suspend fun refreshRequests() {
        val session = _state.value.chat?.llmSessionId ?: return
        runCatching { api.requests(session) }.onSuccess { found -> _state.update { it.copy(requests = found) } }
    }

    private suspend fun refreshSuggestions() {
        val chat = id ?: return
        val now = _state.value
        if (now.live != null || now.lines.lastOrNull()?.isAnswer != true || now.openRequests.isNotEmpty()) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        runCatching { api.suggestions(chat) }.onSuccess { found ->
            _state.update { if (it.live == null) it.copy(suggestions = found) else it }
        }
    }

    private fun fail(message: String) = _state.update { it.copy(error = message) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun draftReturned() = _state.update { it.copy(returnedDraft = null) }

    /** Sends; while a turn is running, what is said goes into it for the agent's next round. */
    fun send(text: String, voice: Boolean = false) {
        val body = text.trim()
        val pending = _state.value.pending
        if (body.isEmpty() && pending.isEmpty()) return
        if (_state.value.live != null) {
            viewModelScope.launch { sayDuringTurn(body, voice) }
            return
        }
        turnJob = viewModelScope.launch { runTurn(body, pending, voice) }
    }

    private suspend fun sayDuringTurn(text: String, voice: Boolean) {
        val chat = id ?: return
        try {
            if (api.postDuringTurn(chat, text)) {
                updateLive { it.copy(steps = it.steps + LiveStep.Person(text)) }
            } else {
                _state.first { it.live == null }
                send(text, voice)
            }
        } catch (failure: IOException) {
            fail(failure.message ?: "That could not be sent.")
            _state.update { it.copy(returnedDraft = text) }
        }
    }

    private suspend fun runTurn(text: String, attachments: List<Attachment>, voice: Boolean) {
        startJob?.join()
        followJob?.cancelAndJoin()
        val before = _state.value
        val mine = ChatMessage(role = "user", content = text, attachments = attachments)
        _state.update {
            it.copy(
                lines = it.lines + mine,
                live = LiveTurn(),
                pending = emptyList(),
                suggestions = emptyList(),
                error = null,
                lastTurn = null,
            )
        }
        listener?.onTurnBegan()
        try {
            val chat = id ?: api.start(workspaceId, before.pickedAgent?.id).let { made ->
                id = made.id
                val moded = if (before.pickedMode != ChatMode.DEFAULT) api.setMode(made.id, before.pickedMode) else made
                _state.update { it.copy(chat = moded) }
                madeHere = true
                refreshEffort()
                made.id
            }
            reader.read(
                first = { onFrame -> api.stream(chat, text, attachments.map(Attachment::id), voice, onFrame) },
                onEvent = ::apply,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: IOException) {
            // Nothing was written: it is theirs again, rather than lost to a failed send.
            _state.update {
                it.copy(
                    lines = it.lines - mine,
                    pending = attachments,
                    returnedDraft = if (voice) null else text,
                    error = refused.message ?: "That could not be sent.",
                )
            }
            listener?.onTurnEvent(TurnEvent.Failed(refused.message ?: "That could not be sent."))
        }
        turnEnded()
    }

    fun regenerate() {
        val chat = id ?: return
        if (_state.value.live != null) return
        turnJob = viewModelScope.launch {
            followJob?.cancelAndJoin()
            _state.update {
                it.copy(
                    lines = it.lines.dropLastWhile { line -> !line.isUser },
                    live = LiveTurn(),
                    suggestions = emptyList(),
                    error = null,
                    lastTurn = null,
                )
            }
            listener?.onTurnBegan()
            try {
                reader.read(first = { onFrame -> api.regenerate(chat, onFrame) }, onEvent = ::apply)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: IOException) {
                fail(refused.message ?: "It could not be asked again.")
            }
            turnEnded()
        }
    }

    /** The history is the record: whatever was drawn live gives way to what it holds. */
    private suspend fun turnEnded() {
        val chat = id
        if (chat != null) {
            runCatching { api.messages(chat) }.onSuccess { lines -> _state.update { it.copy(lines = lines) } }
            runCatching { api.session(chat) }.onSuccess { s -> if (s != null) _state.update { it.copy(chat = s) } }
        }
        _state.update { it.copy(live = null) }
        listener?.onTurnOver()
        refreshRequests()
        refreshEffort()
        if (chat != null) runCatching { api.markRead(chat) }
        refreshSuggestions()
        startFollowing()
    }

    /**
     * Picks up an answer written while nobody watched, then waits for the server
     * to start one by itself: a watcher firing, a reminder coming due, a plan
     * being carried out once approved.
     */
    private fun startFollowing() {
        if (followJob?.isActive == true || id == null) return
        followJob = viewModelScope.launch {
            var wait = false
            var failures = 0
            while (isActive) {
                val chat = id ?: return@launch
                var began = false
                try {
                    reader.read(
                        first = { onFrame -> api.follow(chat, wait, onFrame) },
                        onEvent = { event ->
                            when {
                                event is TurnEvent.Following -> {
                                    began = true
                                    enterFollowedTurn(chat, event.kept)
                                }
                                !event.isControl && began -> apply(event)
                            }
                        },
                        reading = { began },
                    )
                    failures = 0
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    failures++
                    delay((FOLLOW_RETRY_MS * failures).coerceAtMost(FOLLOW_RETRY_MOST_MS))
                }
                if (began) endFollowedTurn(chat)
                wait = true
            }
        }
    }

    private suspend fun enterFollowedTurn(chat: String, kept: Int?) {
        val lines = runCatching { api.messages(chat) }.getOrDefault(_state.value.lines)
        _state.update { it.copy(lines = if (kept != null) lines.take(kept) else lines, live = LiveTurn(), suggestions = emptyList()) }
        listener?.onTurnBegan()
    }

    private suspend fun endFollowedTurn(chat: String) {
        runCatching { api.messages(chat) }.onSuccess { lines -> _state.update { it.copy(lines = lines) } }
        runCatching { api.session(chat) }.onSuccess { s -> if (s != null) _state.update { it.copy(chat = s) } }
        _state.update { it.copy(live = null) }
        listener?.onTurnOver()
        refreshRequests()
        runCatching { api.markRead(chat) }
        refreshSuggestions()
    }

    private fun apply(event: TurnEvent) {
        listener?.onTurnEvent(event)
        when (event) {
            is TurnEvent.Chunk -> updateLive { it.copy(answer = it.answer + event.text, status = null) }
            is TurnEvent.Thinking -> updateLive { it.copy(thinking = it.thinking + event.text, status = null) }
            is TurnEvent.Thought -> updateLive {
                it.copy(thinking = "", steps = it.steps + LiveStep.Thought(event.text, event.millis))
            }
            is TurnEvent.Said -> updateLive { it.copy(steps = it.steps + LiveStep.Said(event.text), status = null) }
            is TurnEvent.Call -> updateLive {
                it.copy(steps = it.steps + LiveStep.Call(event.at, event.tool, event.arguments), status = null)
            }
            is TurnEvent.Called -> {
                updateLive { live ->
                    live.copy(
                        steps = live.steps.map { step ->
                            if (step is LiveStep.Call && step.at == event.at) step.copy(result = event.result, failed = event.failed) else step
                        },
                    )
                }
                viewModelScope.launch { refreshRequests() }
            }
            is TurnEvent.PersonRead -> updateLive { it.copy(steps = markPersonRead(it.steps, event.text)) }
            is TurnEvent.Drew -> updateLive { it.copy(steps = it.steps + LiveStep.Picture(event.markdown)) }
            is TurnEvent.Writing -> updateLive {
                it.copy(status = if (event.tool != null) "Writing ${event.tool}…" else "Writing…")
            }
            is TurnEvent.Reading -> updateLive {
                val share = if (event.total > 0) event.processed * 100 / event.total else 0
                it.copy(status = "Reading the conversation… $share%")
            }
            TurnEvent.Compacting -> updateLive { it.copy(status = "Compacting the conversation…") }
            TurnEvent.Compacted -> updateLive { it.copy(status = null) }
            is TurnEvent.Session -> {
                _state.update { it.copy(chat = it.chat?.copy(llmSessionId = event.id)) }
                viewModelScope.launch { refreshRequests() }
            }
            is TurnEvent.Titled -> _state.update { it.copy(chat = it.chat?.copy(title = event.title)) }
            is TurnEvent.Done -> _state.update { it.copy(lastTurn = spendLine(event)) }
            is TurnEvent.Failed -> fail(event.reason)
            else -> Unit
        }
    }

    private fun updateLive(change: (LiveTurn) -> LiveTurn) = _state.update { s -> s.live?.let { s.copy(live = change(it)) } ?: s }

    /** Stop: the model is hung up on, and nothing of the answer is written to the history. */
    fun stop() {
        val chat = id ?: return
        viewModelScope.launch {
            runCatching { api.interrupt(chat) }.onFailure { fail(it.message ?: "It could not be stopped.") }
        }
    }

    /** For voice: said once whatever turn is closing has closed. */
    fun sayWhenFree(text: String) {
        viewModelScope.launch {
            _state.first { it.live == null }
            send(text, voice = true)
        }
    }

    fun answer(request: InteractionRequest, options: List<String>, text: String?) {
        viewModelScope.launch {
            try {
                api.answer(request.id, options, text?.takeIf(String::isNotBlank))
            } catch (refused: IOException) {
                fail(refused.message ?: "That answer could not be sent.")
            }
            refreshRequests()
            if (request.isPlan) {
                id?.let { chat ->
                    runCatching { api.session(chat) }.onSuccess { s -> if (s != null) _state.update { it.copy(chat = s) } }
                }
            }
        }
    }

    fun chooseAgent(agent: Agent) {
        val chat = id
        if (chat == null) {
            _state.update { it.copy(pickedAgent = agent) }
            return
        }
        viewModelScope.launch {
            try {
                val fresh = api.chooseAgent(chat, agent.id)
                _state.update { it.copy(chat = fresh) }
                // Another model may take other levels, or none.
                refreshEffort()
            } catch (refused: IOException) {
                fail(refused.message ?: "That could not be changed.")
            }
        }
    }

    fun setMode(mode: ChatMode) {
        val chat = id
        if (chat == null) {
            _state.update { it.copy(pickedMode = mode) }
            return
        }
        mutate { api.setMode(chat, mode) }
    }

    fun rename(title: String) {
        val chat = id ?: return
        mutate { api.rename(chat, title) }
    }

    fun setPinned(pinned: Boolean) {
        val chat = id ?: return
        mutate { api.setPinned(chat, pinned) }
    }

    fun delete() {
        val chat = id ?: return
        viewModelScope.launch {
            try {
                followJob?.cancel()
                api.delete(chat)
                _state.update { it.copy(deleted = true) }
            } catch (refused: IOException) {
                fail(refused.message ?: "It could not be deleted.")
            }
        }
    }

    private fun mutate(change: suspend () -> ChatSession) {
        viewModelScope.launch {
            try {
                val fresh = change()
                _state.update { it.copy(chat = fresh) }
            } catch (refused: IOException) {
                fail(refused.message ?: "That could not be changed.")
            }
        }
    }

    fun attach(filename: String, contentType: String, read: () -> ByteArray) {
        viewModelScope.launch {
            _state.update { it.copy(uploading = true) }
            try {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { read() }
                val kept = api.upload(workspaceId, filename, contentType, bytes)
                _state.update { it.copy(pending = it.pending + kept) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: Exception) {
                fail(refused.message ?: "That file could not be uploaded.")
            } finally {
                _state.update { it.copy(uploading = false) }
            }
        }
    }

    fun removePending(attachment: Attachment) = _state.update { it.copy(pending = it.pending - attachment) }

    fun attachmentUrl(id: String) = api.attachmentUrl(id)

    private companion object {
        const val REQUEST_POLL_MS = 3_000L
        const val FOLLOW_RETRY_MS = 3_000L
        const val FOLLOW_RETRY_MOST_MS = 30_000L
    }
}

/** The oldest unread line saying this is now read; one written from elsewhere is added as read. */
fun markPersonRead(steps: List<LiveStep>, text: String): List<LiveStep> {
    val at = steps.indexOfFirst { it is LiveStep.Person && !it.read && it.text == text }
    return if (at >= 0) steps.toMutableList().also { it[at] = LiveStep.Person(text, read = true) } else steps + LiveStep.Person(text, read = true)
}

/**
 * Which line carries the eye: the last thing the person said, once the agent has
 * shown it took it in by doing anything after it. Reading a long prompt does not
 * count, and neither does a question left unanswered by a stopped turn.
 */
fun readLineIndex(lines: List<ChatMessage>, live: LiveTurn?): Int? {
    val last = lines.indexOfLast { it.isUser }
    if (last < 0) return null
    val answeredInHistory = last < lines.lastIndex
    val workingOnIt = live != null && (live.answer.isNotEmpty() || live.thinking.isNotBlank() || live.steps.any { it !is LiveStep.Person })
    return last.takeIf { answeredInHistory || workingOnIt }
}

/** "Answered in 2 seconds · 1.2k in, 340 out · $0.0031". Nothing it does not know. */
fun spendLine(done: TurnEvent.Done): String {
    val parts = mutableListOf("Answered in ${thinkingTime(done.millis)}")
    if (done.inputTokens > 0 || done.outputTokens > 0) parts += "${tokens(done.inputTokens)} in, ${tokens(done.outputTokens)} out"
    done.cost?.takeIf { it > 0 }?.let { parts += "$" + String.format(java.util.Locale.ROOT, "%.4f", it) }
    return parts.joinToString(" · ")
}

fun thinkingTime(millis: Long): String {
    if (millis < 1000) return String.format(java.util.Locale.ROOT, "%.1f seconds", millis / 1000.0)
    val seconds = Math.round(millis / 1000.0)
    return "$seconds second${if (seconds == 1L) "" else "s"}"
}

private fun tokens(count: Long) =
    if (count < 1000) count.toString() else String.format(java.util.Locale.ROOT, "%.1fk", count / 1000.0)
