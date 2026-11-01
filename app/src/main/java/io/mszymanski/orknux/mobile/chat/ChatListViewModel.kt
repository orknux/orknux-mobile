package io.mszymanski.orknux.mobile.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class ChatListState(
    val workspaces: List<Workspace> = emptyList(),
    val workspace: Workspace? = null,
    val chats: List<ChatSession>? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
) {
    val noWorkspaces: Boolean get() = !refreshing && error == null && chats != null && workspace == null
}

class ChatListViewModel(
    private val api: ChatApi,
    initialWorkspace: String?,
    private val rememberWorkspace: (String) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(ChatListState())
    val state: StateFlow<ChatListState> = _state.asStateFlow()
    private var wanted = initialWorkspace

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                val workspaces = api.workspaces()
                val chosen = workspaces.firstOrNull { it.id == wanted } ?: workspaces.firstOrNull()
                // A chat opened and never spoken in is nothing to come back to; it is made on opening, and a killed app cannot tidy it up.
                val chats = chosen?.let { api.sessions(it.id) }.orEmpty().filter { it.lastMessageAt != null }
                _state.update { it.copy(workspaces = workspaces, workspace = chosen, chats = chats, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                _state.update { it.copy(error = failure.message ?: "The chats could not be read.") }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    fun chooseWorkspace(workspace: Workspace) {
        wanted = workspace.id
        rememberWorkspace(workspace.id)
        _state.update { it.copy(workspace = workspace, chats = null) }
        refresh()
    }

    fun setPinned(chat: ChatSession, pinned: Boolean) = change { api.setPinned(chat.id, pinned) }

    fun rename(chat: ChatSession, title: String) = change { api.rename(chat.id, title) }

    fun delete(chat: ChatSession) {
        viewModelScope.launch {
            try {
                api.delete(chat.id)
                _state.update { it.copy(chats = it.chats?.filterNot { one -> one.id == chat.id }) }
            } catch (refused: IOException) {
                _state.update { it.copy(error = refused.message ?: "It could not be deleted.") }
            }
        }
    }

    private fun change(act: suspend () -> ChatSession) {
        viewModelScope.launch {
            try {
                val fresh = act()
                _state.update { it.copy(chats = it.chats?.map { one -> if (one.id == fresh.id) fresh else one }) }
            } catch (refused: IOException) {
                _state.update { it.copy(error = refused.message ?: "That could not be changed.") }
            }
        }
    }
}
