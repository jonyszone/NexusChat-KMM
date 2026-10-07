package com.example.nexuschat.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nexuschat.data.model.AiModel
import com.example.nexuschat.data.model.AvailableModels
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.data.model.ChatMode
import com.example.nexuschat.data.model.ChatRole
import com.example.nexuschat.data.model.TurnState
import com.example.nexuschat.data.network.CredentialCorruptedException
import com.example.nexuschat.data.network.LlmHttpException
import com.example.nexuschat.data.network.MissingApiKeyException
import com.example.nexuschat.domain.repository.ChatModeStore
import com.example.nexuschat.domain.repository.ChatRepository
import com.example.nexuschat.domain.repository.ChatSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-shot UI events (snackbar, nav to Settings for BYOK, …). */
sealed interface ChatEvent {
    /** BYOK mode was selected but no key is stored for the provider. */
    data class NeedsApiKey(val providerName: String) : ChatEvent

    /** A stored key could not be decrypted; the user must re-enter it. */
    data class CredentialError(val providerName: String) : ChatEvent

    /** Stream / persistence failure with a sanitized message. */
    data class StreamFailed(val message: String) : ChatEvent
}

/** Transient overlay for the in-flight attempt; the DB row stays hidden until it settles. */
data class PendingTurn(
    val sessionId: String,
    val turnId: String,
    val attemptId: String,
    val assistantMessageId: String,
    val text: String
)

/** Single source of truth for ChatScreen. */
data class ChatUiState(
    val mode: ChatMode = ChatMode.DEMO,
    val sessions: List<ChatSession> = emptyList(),
    val selectedSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val pending: PendingTurn? = null,
    val currentModel: AiModel = AvailableModels.Gpt4oMini,
    val systemPrompt: String? = null,
    val isStreaming: Boolean = false,
    val lastEvent: ChatEvent? = null
) {
    /** Smoothly updating partial text for the active attempt. */
    val streamingText: String get() = pending?.text.orEmpty()

    /** Canonical history with the in-flight assistant row collapsed into the overlay. */
    val displayMessages: List<ChatMessage>
        get() = messages.filterNot { it.id == pending?.assistantMessageId }
}

/**
 * Shared ViewModel (Android / iOS via kmp-viewmodel / Desktop).
 *
 * - Persisted timeline is canonical; a transient [PendingTurn] overlays only the
 *   active attempt so assistant text is never concatenated twice.
 * - send() persists the user message and an assistant placeholder *before* any
 *   network call, then checkpoints partial output at a bounded cadence.
 * - Stream state is a try/catch/finally machine with a generation guard so a
 *   stale attempt's cleanup can never overwrite a newer send.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val repository: ChatRepository,
    private val modeStore: ChatModeStore,
    private val savedState: ChatSavedState? = null,
    private val clock: () -> Long = { ChatMessage.now() },
    private val idGenerator: () -> String = { ChatMessage.randomId() }
) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private val selectedSessionId = MutableStateFlow<String?>(savedState?.getString(KEY_SELECTED_SESSION))
    private val recoveredSessions = mutableSetOf<String>()

    private var streamJob: Job? = null
    private var generation: Long = 0

    init {
        viewModelScope.launch {
            modeStore.mode.collect { mode ->
                _ui.update { it.copy(mode = mode) }
            }
        }
        viewModelScope.launch {
            repository.observeSessions().collect { sessions ->
                _ui.update { it.copy(sessions = sessions) }
                sessions.forEach { session ->
                    if (recoveredSessions.add(session.id)) {
                        launch { runCatching { repository.recoverInterrupted(session.id) } }
                    }
                }
                val current = selectedSessionId.value
                if (sessions.none { it.id == current }) {
                    val restored = savedState?.getString(KEY_SELECTED_SESSION)
                    selectedSessionId.value = restored?.takeIf { id -> sessions.any { it.id == id } }
                        ?: sessions.firstOrNull()?.id
                    savedState?.setString(KEY_SELECTED_SESSION, selectedSessionId.value)
                    _ui.update { it.copy(selectedSessionId = selectedSessionId.value) }
                }
            }
        }
        viewModelScope.launch {
            selectedSessionId
                .flatMapLatest { id ->
                    if (id == null) flowOf(emptyList()) else repository.observeMessages(id)
                }
                .collect { persisted ->
                    _ui.update { it.copy(messages = persisted) }
                }
        }
    }

    fun selectSession(sessionId: String) {
        if (selectedSessionId.value == sessionId) return
        cancelStreamForSessionSwitch()
        selectedSessionId.value = sessionId
        savedState?.setString(KEY_SELECTED_SESSION, sessionId)
        _ui.update { it.copy(selectedSessionId = sessionId) }
    }

    fun newSession(): String {
        val now = clock()
        val mode = _ui.value.mode
        val session = ChatSession(
            id = idGenerator(),
            title = "New chat",
            modelId = _ui.value.currentModel.id,
            systemPrompt = _ui.value.systemPrompt,
            mode = mode,
            createdAt = now,
            updatedAt = now
        )
        viewModelScope.launch {
            repository.createSession(session)
            selectedSessionId.value = session.id
            savedState?.setString(KEY_SELECTED_SESSION, session.id)
            _ui.update { it.copy(selectedSessionId = session.id) }
        }
        return session.id
    }

    fun deleteSession(sessionId: String) {
        // Deleting a session must cancel its stream before removing rows.
        if (selectedSessionId.value == sessionId) cancelStreamForSessionSwitch()
        viewModelScope.launch { repository.deleteSession(sessionId) }
    }

    fun renameSession(sessionId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.renameSession(sessionId, trimmed) }
    }

    /** Clear durable history for the selected session (not just UI state). */
    fun clearCurrentSession() {
        val sessionId = selectedSessionId.value ?: return
        cancelStreamForSessionSwitch()
        viewModelScope.launch { repository.clearMessages(sessionId) }
    }

    fun setMode(mode: ChatMode) {
        if (_ui.value.mode == mode) return
        cancelStreamForSessionSwitch()
        selectedSessionId.value = null
        savedState?.setString(KEY_SELECTED_SESSION, null)
        _ui.update { it.copy(selectedSessionId = null, messages = emptyList()) }
        viewModelScope.launch { modeStore.setMode(mode) }
    }

    fun switchModel(model: AiModel) {
        _ui.update { it.copy(currentModel = model, lastEvent = null) }
    }

    fun setSystemPrompt(prompt: String?) {
        _ui.update { it.copy(systemPrompt = prompt?.takeIf { s -> s.isNotBlank() }) }
    }

    fun draft(sessionId: String?): String = sessionId?.let { savedState?.getString(draftKey(it)) }.orEmpty()

    fun setDraft(sessionId: String?, value: String) {
        sessionId ?: return
        savedState?.setString(draftKey(sessionId), value.take(MAX_DRAFT_LENGTH).ifEmpty { null })
    }

    fun consumeEvent() {
        _ui.update { it.copy(lastEvent = null) }
    }

    fun cancel() {
        streamJob?.cancel()
        streamJob = null
    }

    fun send(userText: String) {
        val text = userText.trim()
        val snapshot = _ui.value
        if (text.isEmpty() || snapshot.isStreaming) return
        val sessionId = snapshot.selectedSessionId ?: run { newSession(); return }

        val turnId = idGenerator()
        val attemptId = idGenerator()
        val userMsg = ChatMessage.user(text, turnId = turnId, attemptId = attemptId, timestamp = clock())
        val placeholder = ChatMessage.assistant(
            content = "",
            modelId = snapshot.currentModel.id,
            id = idGenerator(),
            turnId = turnId,
            attemptId = attemptId,
            state = TurnState.PENDING,
            provider = snapshot.currentModel.provider,
            timestamp = clock()
        )
        val history = snapshot.displayMessages
            .filter { it.state == TurnState.COMPLETED } + userMsg
        launchTurn(sessionId, userMsg, placeholder, history, snapshot.currentModel, snapshot.systemPrompt)
    }

    /**
     * Retry a failed/interrupted/cancelled turn using the same user message and
     * turn identity with a new attempt. Regeneration of a *completed* turn is a
     * distinct explicit action and is intentionally not performed here.
     */
    fun retryLast() {
        val snapshot = _ui.value
        if (snapshot.isStreaming) return
        val sessionId = snapshot.selectedSessionId ?: return
        val messages = snapshot.messages
        val lastUser = messages.lastOrNull { it.role == ChatRole.USER } ?: return
        val turnId = lastUser.turnId ?: return
        val completed = messages.any {
            it.role == ChatRole.ASSISTANT && it.turnId == turnId && it.state == TurnState.COMPLETED
        }
        if (completed) return

        val attemptId = idGenerator()
        // Reuse the same assistant row so a turn never accumulates duplicate answers.
        val previousAssistant = messages.lastOrNull { it.role == ChatRole.ASSISTANT && it.turnId == turnId }
        val placeholder = ChatMessage.assistant(
            content = "",
            modelId = snapshot.currentModel.id,
            id = previousAssistant?.id ?: idGenerator(),
            turnId = turnId,
            attemptId = attemptId,
            state = TurnState.PENDING,
            provider = snapshot.currentModel.provider,
            timestamp = clock()
        )
        val history = messages
            .filter { it.state == TurnState.COMPLETED && it.id != placeholder.id } + lastUser
        launchTurn(sessionId, lastUser, placeholder, history, snapshot.currentModel, snapshot.systemPrompt)
    }

    fun retryAvailable(): Boolean {
        val snapshot = _ui.value
        if (snapshot.isStreaming) return false
        val lastUser = snapshot.messages.lastOrNull { it.role == ChatRole.USER } ?: return false
        val turnId = lastUser.turnId ?: return false
        return snapshot.messages.none {
            it.role == ChatRole.ASSISTANT && it.turnId == turnId && it.state == TurnState.COMPLETED
        }
    }

    private fun cancelStreamForSessionSwitch() {
        val job = streamJob
        streamJob = null
        generation += 1
        job?.cancel()
        _ui.update { it.copy(isStreaming = false, pending = null) }
    }

    private fun launchTurn(
        sessionId: String,
        userMsg: ChatMessage,
        placeholder: ChatMessage,
        history: List<ChatMessage>,
        model: AiModel,
        systemPrompt: String?
    ) {
        streamJob?.cancel()
        val myGeneration = ++generation
        _ui.update {
            it.copy(
                pending = PendingTurn(sessionId, placeholder.turnId!!, placeholder.attemptId!!, placeholder.id, ""),
                isStreaming = true,
                lastEvent = null
            )
        }

        streamJob = viewModelScope.launch {
            // 1) Durable pre-request write: user input and placeholder survive a crash.
            try {
                repository.beginTurn(sessionId, userMsg, placeholder, model, systemPrompt, clock())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (myGeneration == generation) {
                    _ui.update {
                        it.copy(isStreaming = false, pending = null, lastEvent = eventFor(e))
                    }
                }
                return@launch
            }

            val acc = StringBuilder()
            var tokenCount = 0
            var finalState = TurnState.COMPLETED
            var errorCategory: String? = null
            var event: ChatEvent? = null

            try {
                repository.streamReply(model, history, systemPrompt).collect { token ->
                    acc.append(token)
                    tokenCount += 1
                    if (myGeneration == generation) {
                        _ui.update { s ->
                            val pending = s.pending
                            if (pending != null && pending.attemptId == placeholder.attemptId) {
                                s.copy(pending = pending.copy(text = acc.toString()))
                            } else {
                                s
                            }
                        }
                    }
                    if (tokenCount % CHECKPOINT_EVERY_TOKENS == 0) {
                        runCatching {
                            repository.upsertMessage(
                                sessionId,
                                placeholder.copy(content = acc.toString(), state = TurnState.STREAMING)
                            )
                        }
                    }
                }
                if (acc.isEmpty()) {
                    finalState = TurnState.FAILED
                    errorCategory = "empty_response"
                    event = ChatEvent.StreamFailed("The provider returned an empty response.")
                }
            } catch (e: CancellationException) {
                finalState = TurnState.CANCELLED
                throw e
            } catch (e: Exception) {
                finalState = TurnState.FAILED
                errorCategory = classify(e)
                event = eventFor(e)
            } finally {
                withContext(NonCancellable) {
                    if (myGeneration == generation) {
                        runCatching {
                            repository.upsertMessage(
                                sessionId,
                                placeholder.copy(
                                    content = acc.toString(),
                                    state = finalState,
                                    errorCategory = errorCategory
                                )
                            )
                        }
                        _ui.update { s ->
                            if (s.pending?.attemptId == placeholder.attemptId) {
                                s.copy(isStreaming = false, pending = null, lastEvent = event ?: s.lastEvent)
                            } else {
                                s
                            }
                        }
                    }
                }
            }
        }
    }

    private fun classify(e: Throwable): String = when (e) {
        is MissingApiKeyException -> "missing_key"
        is CredentialCorruptedException -> "credential_corrupt"
        is LlmHttpException -> "http_${e.status}"
        is com.example.nexuschat.data.network.LlmStreamException -> "stream_${e.category}"
        else -> e::class.simpleName ?: "error"
    }

    private fun eventFor(e: Throwable): ChatEvent = when (e) {
        is MissingApiKeyException -> ChatEvent.NeedsApiKey(e.provider.name)
        is CredentialCorruptedException -> ChatEvent.CredentialError(e.provider.name)
        is LlmHttpException -> ChatEvent.StreamFailed(httpMessage(e))
        is com.example.nexuschat.data.network.LlmStreamException -> ChatEvent.StreamFailed(streamMessage(e))
        else -> ChatEvent.StreamFailed("Stream failed: ${e::class.simpleName ?: "error"}")
    }

    private fun streamMessage(e: com.example.nexuschat.data.network.LlmStreamException): String = when (e.category) {
        "refusal" -> "The provider blocked this response."
        "unexpected_eof" -> "The response ended unexpectedly. Partial text was kept."
        "stream_error" -> "The provider reported an error mid-stream."
        else -> "The response could not be read."
    }

    private fun httpMessage(e: LlmHttpException): String = when (e.status) {
        401, 403 -> "Authentication failed for this provider. Check your API key."
        404 -> "That model is not available for this key."
        429 -> "Rate limited by the provider. Try again later."
        in 500..599 -> "The provider had a server error. Try again later."
        else -> "Provider error (HTTP ${e.status})."
    }

    private companion object {
        const val CHECKPOINT_EVERY_TOKENS = 16
        const val KEY_SELECTED_SESSION = "nexus.selected_session"
        const val MAX_DRAFT_LENGTH = 16_384
        fun draftKey(sessionId: String) = "nexus.draft.$sessionId"
    }
}
