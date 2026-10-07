package com.example.nexuschat.data.demo

import com.example.nexuschat.data.model.AiModel
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.data.model.ChatMode
import com.example.nexuschat.data.model.ChatRole
import com.example.nexuschat.data.model.TurnState
import com.example.nexuschat.domain.repository.ChatRepository
import com.example.nexuschat.domain.repository.ChatSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Deterministic, in-memory backend for DEMO mode. It never touches credentials
 * or the network, and it is isolated from the real BYOK database so seeded
 * content can never leak into paid-provider history.
 */
class DemoChatRepository : ChatRepository {

    override val mode: ChatMode = ChatMode.DEMO

    private val sessions = MutableStateFlow(
        listOf(
            ChatSession(
                id = DEMO_SESSION_ID,
                title = "NexusChat demo",
                modelId = "gpt-4o-mini",
                mode = ChatMode.DEMO,
                createdAt = 1728000000000,
                updatedAt = 1728000120000
            )
        )
    )

    private val messagesBySession = MutableStateFlow<Map<String, List<ChatMessage>>>(
        mapOf(DEMO_SESSION_ID to seeded())
    )

    override fun streamReply(model: AiModel, history: List<ChatMessage>, systemPrompt: String?): Flow<String> = flow {
        val prompt = history.lastOrNull { it.role == ChatRole.USER }?.content.orEmpty()
        val response = when {
            prompt.contains("hello", ignoreCase = true) || prompt.contains("hi", ignoreCase = true) ->
                "Hello from the NexusChat demo repository."
            prompt.contains("help", ignoreCase = true) ->
                "The demo supports navigation, chat state, send, streaming, retry, cancel, and local seeded content."
            else -> "Demo response received: $prompt\n\nThe repository boundary is ready for the real LLM implementation."
        }
        response.split(" ").forEach { token ->
            emit("$token ")
            delay(18)
        }
    }

    override fun observeSessions(): Flow<List<ChatSession>> = sessions.asStateFlow()

    override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        messagesBySession
            .map { it[sessionId].orEmpty() }
            .distinctUntilChanged()

    override suspend fun createSession(session: ChatSession) {
        sessions.value = (sessions.value.filterNot { it.id == session.id } + session)
            .sortedWith(compareByDescending<ChatSession> { it.updatedAt }.thenBy { it.id })
        if (messagesBySession.value[session.id] == null) {
            messagesBySession.value = messagesBySession.value + (session.id to emptyList())
        }
    }

    override suspend fun renameSession(sessionId: String, title: String) {
        sessions.value = sessions.value.map { if (it.id == sessionId) it.copy(title = title) else it }
    }

    override suspend fun deleteSession(sessionId: String) {
        sessions.value = sessions.value.filterNot { it.id == sessionId }
        messagesBySession.value = messagesBySession.value - sessionId
    }

    override suspend fun clearMessages(sessionId: String) {
        if (messagesBySession.value.containsKey(sessionId)) {
            messagesBySession.value = messagesBySession.value + (sessionId to emptyList())
        }
    }

    override suspend fun beginTurn(
        sessionId: String,
        user: ChatMessage,
        assistantPlaceholder: ChatMessage,
        model: AiModel,
        systemPrompt: String?,
        updatedAt: Long
    ) {
        var messages = messagesBySession.value[sessionId].orEmpty()
        var seq = messages.maxOfOrNull { it.sequence } ?: 0L
        listOf(user, assistantPlaceholder).forEach { message ->
            if (messages.none { it.id == message.id }) {
                seq += 1
                messages = messages + message.copy(sequence = seq)
            }
        }
        messagesBySession.value = messagesBySession.value + (sessionId to messages)
        sessions.value = sessions.value.map {
            if (it.id == sessionId) {
                it.copy(modelId = model.id, systemPrompt = systemPrompt, updatedAt = updatedAt)
            } else {
                it
            }
        }.sortedWith(compareByDescending<ChatSession> { it.updatedAt }.thenBy { it.id })
    }

    override suspend fun upsertMessage(sessionId: String, message: ChatMessage) {
        val current = messagesBySession.value[sessionId] ?: return
        val existing = current.firstOrNull { it.id == message.id }
        val updated = if (existing == null) {
            val seq = (current.maxOfOrNull { it.sequence } ?: 0L) + 1
            current + message.copy(sequence = seq)
        } else {
            current.map { if (it.id == message.id) message.copy(sequence = existing.sequence) else it }
        }
        messagesBySession.value = messagesBySession.value + (sessionId to updated)
    }

    override suspend fun recoverInterrupted(sessionId: String) {
        val current = messagesBySession.value[sessionId] ?: return
        val updated = current.map {
            if (it.state.isActive) it.copy(state = TurnState.INTERRUPTED) else it
        }
        messagesBySession.value = messagesBySession.value + (sessionId to updated)
    }

    companion object {
        const val DEMO_SESSION_ID = "demo-general"

        private fun seeded(): List<ChatMessage> = listOf(
            ChatMessage.assistant(
                "Welcome to NexusChat. This is local demo data for the first functional build.",
                modelId = "gpt-4o-mini",
                id = "demo-welcome"
            ),
            ChatMessage.user("What can I do here?", id = "demo-question"),
            ChatMessage.assistant(
                "Try the Chats, Updates, Communities, Calls, Profile, and Settings flows. Sending a message uses the demo repository until the real backend is connected.",
                modelId = "gpt-4o-mini",
                id = "demo-answer"
            )
        ).mapIndexed { index, message -> message.copy(sequence = index + 1L) }
    }
}
