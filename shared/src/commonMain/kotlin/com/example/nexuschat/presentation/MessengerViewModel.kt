package com.example.nexuschat.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.domain.messenger.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

data class MessengerUiState(
    val restoring: Boolean = true,
    val signedIn: Boolean = false,
    val serverUrl: String = "",
    val accountId: String = "",
    val conversations: List<CachedConversation> = emptyList(),
    val selectedId: String? = null,
    val messages: List<WireMessage> = emptyList(),
    val pending: List<PendingMessage> = emptyList(),
    val draft: String = "",
    val busy: Boolean = false,
    val syncing: Boolean = false,
    val realtimeConnected: Boolean = false,
    val error: String? = null,
)

class MessengerViewModel(
    private val api: AuthenticatedMessagingApi,
    private val sessions: MessengerSessionStore,
    private val storage: MessengerStorage,
    private val savedState: ChatSavedState? = null,
    private val clock: () -> Long = { ChatMessage.now() },
    private val key: () -> String = { Uuid.random().toString() },
    private val updates: MessengerUpdates? = null,
) : ViewModel() {
    private val state = MutableStateFlow(MessengerUiState())
    val ui: StateFlow<MessengerUiState> = state.asStateFlow()
    private val mutex = Mutex()
    private var session: MessengerSession? = null
    private var foreground = false
    private var polling: Job? = null
    private var realtime: Job? = null
    private var realtimeGeneration = 0L

    init {
        viewModelScope.launch {
            mutex.withLock {
                try {
                    val restored = sessions.load()
                    if (restored != null) {
                        if (restored.expiresAt <= clock() / 1000) {
                            sessions.clear()
                            state.update { it.copy(serverUrl = restored.serverUrl, error = "Your session expired. Sign in again.") }
                        } else {
                            session = restored
                            state.update { it.copy(signedIn = true, serverUrl = restored.serverUrl, accountId = restored.accountId,
                                selectedId = savedState?.getString(selectionKey(restored))) }
                            storage.recover(restored.identity)
                            loadLocal(restored)
                            api.verify(restored)
                            syncLocked(restored)
                        }
                    }
                } catch (cause: CancellationException) { throw cause
                } catch (cause: Exception) { failed(cause)
                } finally { state.update { it.copy(restoring = false) } }
            }
            startPolling()
        }
    }

    fun authenticate(server: String, email: String, password: String, register: Boolean) = runAction {
        val authenticated = api.authenticate(server, email, password, register)
        sessions.save(authenticated)
        session = authenticated
        state.value = MessengerUiState(restoring = false, signedIn = true, serverUrl = authenticated.serverUrl,
            accountId = authenticated.accountId, busy = true, selectedId = savedState?.getString(selectionKey(authenticated)))
        storage.recover(authenticated.identity)
        loadLocal(authenticated)
        syncLocked(authenticated)
        startPolling()
    }

    fun logout() = runAction {
        val previous = session ?: return@runAction
        sessions.clear()
        session = null
        polling?.cancel(); polling = null
        stopRealtime()
        state.value = MessengerUiState(restoring = false, serverUrl = previous.serverUrl, busy = true)
        try { api.revoke(previous)
        } catch (cause: CancellationException) { throw cause
        } catch (_: Exception) { state.update { it.copy(error = "Signed out locally. Server session revocation could not be confirmed.") } }
    }

    fun createConversation(peer: String) = runAction {
        val current = requireSession()
        val conversation = api.createConversation(current, peer.trim())
        storage.saveConversations(current.identity, listOf(conversation))
        selectLocked(current, conversation.id)
        syncLocked(current)
    }

    fun selectConversation(id: String) = runAction {
        val current = requireSession()
        check(state.value.conversations.any { it.id == id })
        selectLocked(current, id)
        syncLocked(current)
    }

    fun back(): Boolean {
        if (state.value.selectedId == null) return false
        if (state.value.busy) return true
        session?.let { savedState?.setString(selectionKey(it), null) }
        state.update { it.copy(selectedId = null, messages = emptyList(), pending = emptyList(), draft = "", error = null) }
        return true
    }

    fun setDraft(value: String) {
        val current = session ?: return
        val id = state.value.selectedId ?: return
        val draft = value.take(16_384)
        savedState?.setString(draftKey(current, id), draft)
        state.update { it.copy(draft = draft) }
    }

    fun send() {
        val text = state.value.draft.trim()
        val id = state.value.selectedId ?: return
        if (text.isEmpty()) return
        runAction {
            val current = requireSession()
            storage.enqueue(current.identity, PendingMessage(key(), id, text, "PENDING"))
            if (state.value.selectedId == id && state.value.draft.trim() == text) setDraft("")
            loadLocal(current)
            flush(current)
            syncLocked(current)
        }
    }

    fun retry(messageKey: String) = runAction {
        val current = requireSession()
        storage.setPendingState(current.identity, messageKey, "PENDING")
        flush(current)
        syncLocked(current)
    }

    fun refresh() = runAction { syncLocked(requireSession()) }
    fun dismissError() { state.update { it.copy(error = null) } }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) startPolling() else {
            polling?.cancel(); polling = null
            stopRealtime()
            state.update { it.copy(realtimeConnected = false) }
        }
    }

    private fun startPolling() {
        startRealtime()
        if (!foreground || session == null || polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (foreground && session != null) {
                delay(5_000)
                mutex.withLock {
                    val current = session ?: return@withLock
                    state.update { it.copy(syncing = true) }
                    try { syncLocked(current)
                    } catch (cause: CancellationException) { throw cause
                    } catch (cause: Exception) { failed(cause)
                    } finally { state.update { it.copy(syncing = false) } }
                }
            }
        }
    }

    private fun startRealtime() {
        val source = updates ?: return
        val current = session ?: return
        if (!foreground || realtime?.isActive == true) return
        val generation = ++realtimeGeneration
        realtime = viewModelScope.launch {
            var retryDelay = 1_000L
            try {
                while (isActive && foreground && session === current) {
                    try {
                        source.events(current).conflate().collect {
                            mutex.withLock {
                                if (session !== current || !foreground) return@withLock
                                retryDelay = 1_000L
                                state.update { it.copy(realtimeConnected = true, syncing = true) }
                                try { syncLocked(current)
                                } catch (cause: CancellationException) { throw cause
                                } catch (cause: Exception) { failed(cause)
                                } finally { state.update { it.copy(syncing = false) } }
                            }
                        }
                    } catch (cause: CancellationException) { throw cause
                    } catch (_: Exception) { /* HTTP fallback verifies authorization and retains queued work. */ }
                    state.update { it.copy(realtimeConnected = false) }
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
                }
            } finally {
                if (realtimeGeneration == generation && session === current) state.update { it.copy(realtimeConnected = false) }
            }
        }
    }

    private fun stopRealtime() {
        realtimeGeneration++
        realtime?.cancel()
        realtime = null
    }

    private fun runAction(action: suspend () -> Unit) {
        if (state.value.busy || state.value.restoring) return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            mutex.withLock {
                try { action()
                } catch (cause: CancellationException) { throw cause
                } catch (cause: Exception) { failed(cause)
                } finally { state.update { it.copy(busy = false) } }
            }
        }
    }

    private suspend fun selectLocked(current: MessengerSession, id: String) {
        savedState?.setString(selectionKey(current), id)
        state.update { it.copy(selectedId = id, messages = emptyList(), pending = emptyList(), draft = "") }
        loadLocal(current)
    }

    private suspend fun loadLocal(current: MessengerSession, authorized: Set<String>? = null) {
        val conversations = storage.conversations(current.identity).filter { authorized == null || it.id in authorized }
        val id = state.value.selectedId?.takeIf { selected -> conversations.any { it.id == selected } }
        val messages = if (id == null) emptyList() else storage.messages(current.identity, id)
        val pending = storage.outbox(current.identity).filter { it.conversationId == id }
        state.update { previous -> previous.copy(conversations = conversations, selectedId = id, messages = messages, pending = pending,
            draft = id?.let { selected -> savedState?.getString(draftKey(current, selected)) ?: previous.draft.takeIf { previous.selectedId == selected } }.orEmpty()) }
    }

    private suspend fun syncLocked(current: MessengerSession) {
        val conversations = api.conversations(current)
        storage.saveConversations(current.identity, conversations)
        val authorized = conversations.map { it.id }.toSet()
        loadLocal(current, authorized)
        val sendsSucceeded = flush(current)
        val id = state.value.selectedId
        if (id != null && id in authorized) {
            val after = storage.conversations(current.identity).first { it.id == id }.cursor
            val messages = api.history(current, id, after)
            storage.mergeHistory(current.identity, id, after, messages)
            loadLocal(current, authorized)
            val cursor = messages.lastOrNull()?.sequence ?: after
            if (cursor > 0) api.acknowledge(current, id, cursor)
        }
        loadLocal(current, authorized)
        if (sendsSucceeded) state.update { it.copy(error = null) }
    }

    private suspend fun flush(current: MessengerSession): Boolean {
        var succeeded = true
        for (message in storage.outbox(current.identity).filter { it.state == "PENDING" }) {
            storage.setPendingState(current.identity, message.key, "SENDING")
            try {
                storage.accept(current.identity, api.send(current, message.conversationId, message.key, message.body))
            } catch (cause: CancellationException) {
                withContext(NonCancellable) { storage.setPendingState(current.identity, message.key, "PENDING") }
                throw cause
            } catch (cause: Exception) {
                succeeded = false
                storage.setPendingState(current.identity, message.key, "FAILED")
                if (cause is MessagingHttpException && cause.status == 401) throw cause
                state.update { it.copy(error = errorMessage(cause)) }
            }
        }
        loadLocal(current)
        return succeeded
    }

    private suspend fun failed(cause: Exception) {
        if (cause is MessagingHttpException && cause.status == 401 && session != null) {
            val server = session!!.serverUrl
            session = null
            try { sessions.clear() } catch (_: Exception) { /* Remains signed out if secure storage is unavailable. */ }
            state.value = MessengerUiState(restoring = false, serverUrl = server, error = "Your session expired. Sign in again.")
            polling?.cancel(); polling = null
            stopRealtime()
        } else state.update { it.copy(error = errorMessage(cause)) }
    }

    private fun errorMessage(cause: Exception): String = when (cause) {
        is MessagingHttpException -> when (cause.status) {
            401 -> "Email or password was rejected."
            403 -> "You do not have access to this conversation."
            404 -> "Account or conversation was not found."
            409 -> "The account already exists or the request conflicts with saved data."
            400, 422 -> "Check the account details and try again."
            else -> "The messaging server is unavailable. Retry when connected."
        }
        is MessagingProtocolException -> "The server response could not be verified."
        is IllegalArgumentException -> "Check the server address and account ID."
        else -> "Unable to sync. Your saved messages and queued sends are kept."
    }

    private fun requireSession() = checkNotNull(session)
    private fun selectionKey(current: MessengerSession) = "messenger.selected.${current.serverUrl}.${current.accountId}"
    private fun draftKey(current: MessengerSession, id: String) = "messenger.draft.${current.serverUrl}.${current.accountId}.$id"
}
