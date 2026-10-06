package com.example.nexuschat.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nexuschat.data.model.AiModel
import com.example.nexuschat.data.model.AvailableModels
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.domain.repository.ChatSession
import com.example.nexuschat.data.network.MissingApiKeyException
import com.example.nexuschat.domain.repository.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One-shot UI events (snackbar, nav to Settings for BYOK, …). */
sealed interface ChatEvent {
    data class NeedsApiKey(val providerName: String) : ChatEvent
    data class StreamFailed(val message: String) : ChatEvent
}

/** Single source of truth for ChatScreen. */
data class ChatUiState(
    val sessions: List<ChatSession> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val streamingText: String = "",
    val isStreaming: Boolean = false,
    val currentModel: AiModel = AvailableModels.Gpt4oMini,
    val systemPrompt: String? = null,
    val lastEvent: ChatEvent? = null
)

/**
 * Shared ViewModel (Android / iOS via kmp-viewmodel / Desktop).
 * - switchModel() mid-conversation only affects *next* turn (history keeps modelId per message).
 * - send() appends USER optimistically, then collects Flow<String> tokens into streamingText.
 * - cancel() aborts the in-flight collection Job.
 */
class ChatViewModel(
    private val repository: ChatRepository
) : ViewModel() {

    private val sessionId = "demo-general"

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var streamJob: Job? = null

    init {
        viewModelScope.launch {
            repository.ensureSession(
                ChatSession(
                    id = sessionId,
                    title = "NexusChat demo",
                    modelId = AvailableModels.Gpt4oMini.id,
                    createdAt = 0L,
                    updatedAt = 0L
                )
            )
        }
        viewModelScope.launch {
            repository.observeSessions().collect { sessions ->
                _ui.update { it.copy(sessions = sessions) }
            }
        }
        viewModelScope.launch {
            repository.observeMessages(sessionId).collect { persisted ->
                if (!_ui.value.isStreaming) {
                    _ui.update { it.copy(messages = persisted) }
                }
            }
        }
    }

    fun switchModel(model: AiModel) {
        _ui.update { it.copy(currentModel = model, lastEvent = null) }
    }

    fun setSystemPrompt(prompt: String?) {
        _ui.update { it.copy(systemPrompt = prompt?.takeIf { s -> s.isNotBlank() }) }
    }

    fun consumeEvent() {
        _ui.update { it.copy(lastEvent = null) }
    }

    fun cancel() {
        streamJob?.cancel()
        streamJob = null
        _ui.update { it.copy(isStreaming = false) }
    }

    fun send(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || _ui.value.isStreaming) return

        val snapshot = _ui.value
        val userMsg = ChatMessage.user(text)
        val historyForLlm = snapshot.messages + userMsg

        _ui.update {
            it.copy(
                messages = it.messages + userMsg,
                streamingText = "",
                isStreaming = true,
                lastEvent = null
            )
        }

        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            val acc = StringBuilder()
            repository.streamReply(snapshot.currentModel, historyForLlm, snapshot.systemPrompt)
                .catch { e ->
                    val event = when (e) {
                        is MissingApiKeyException ->
                            ChatEvent.NeedsApiKey(e.provider.name)
                        else -> ChatEvent.StreamFailed(e.message ?: "Stream failed")
                    }
                    _ui.update { s -> s.copy(isStreaming = false, lastEvent = event) }
                }
                .onCompletion { cause ->
                    // Only finalize an assistant bubble on clean completion with content.
                    if (cause == null && acc.isNotEmpty()) {
                        val assistant = ChatMessage.assistant(
                            content = acc.toString(),
                            modelId = snapshot.currentModel.id
                        )
                        repository.persistTurn(sessionId, userMsg, assistant)
                        _ui.update { s ->
                            s.copy(
                                messages = s.messages + assistant,
                                streamingText = "",
                                isStreaming = false
                            )
                        }
                    } else if (cause != null) {
                        // Keep partial text visible so user can copy / retry.
                        _ui.update { s -> s.copy(isStreaming = false) }
                    }
                }
                .collect { token ->
                    acc.append(token)
                    _ui.update { s -> s.copy(streamingText = acc.toString()) }
                }
        }
    }

    fun retryLast() {
        val lastUser = _ui.value.messages.lastOrNull { it.role == com.example.nexuschat.data.model.ChatRole.USER }
            ?: return
        // Remove optimistic assistant-less tail? We keep history, LLM is stateless on history list.
        // Simplest UX: resend last user text as a new turn.
        _ui.update { it.copy(messages = it.messages.filterNot { m -> m.id == lastUser.id }) }
        send(lastUser.content)
    }

    fun clear() {
        cancel()
        _ui.update { it.copy(messages = emptyList(), streamingText = "", lastEvent = null) }
    }
}
