package com.example.nexuschat.domain.repository

import com.example.nexuschat.data.model.AiModel
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.data.network.KtorLlmStreamingClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Chat session metadata. Message rows live separately so a session
 * list query stays cheap (SQLDelight / Room backing).
 */
data class ChatSession(
    val id: String,
    val title: String,
    val modelId: String,
    val systemPrompt: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Local persistence boundary. Implemented once with SQLDelight
 * (or Room KMP) — repository stays platform-agnostic and testable.
 * Provided as `expect/actual` or constructor injection per target.
 */
interface ChatStorage {
    fun observeSessions(): Flow<List<ChatSession>>
    fun observeMessages(sessionId: String): Flow<List<ChatMessage>>
    suspend fun upsertSession(session: ChatSession)
    suspend fun replaceMessages(sessionId: String, messages: List<ChatMessage>)
    suspend fun appendMessage(sessionId: String, message: ChatMessage)
    suspend fun deleteSession(sessionId: String)
}

/** No-op storage for previews / JVM unit tests without SQL driver. */
class InMemoryChatStorage : ChatStorage {
    private val sessions = kotlinx.coroutines.flow.MutableStateFlow<List<ChatSession>>(emptyList())
    private val bySession = mutableMapOf<String, kotlinx.coroutines.flow.MutableStateFlow<List<ChatMessage>>>()

    override fun observeSessions(): Flow<List<ChatSession>> = sessions

    override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        bySession.getOrPut(sessionId) {
            kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        }

    override suspend fun upsertSession(session: ChatSession) {
        sessions.value = (sessions.value.filterNot { it.id == session.id } + session)
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun replaceMessages(sessionId: String, messages: List<ChatMessage>) {
        observeMessages(sessionId).let { (it as kotlinx.coroutines.flow.MutableStateFlow).value = messages }
    }

    override suspend fun appendMessage(sessionId: String, message: ChatMessage) {
        val flow = bySession.getOrPut(sessionId) {
            kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        }
        flow.value = flow.value + message
    }

    override suspend fun deleteSession(sessionId: String) {
        sessions.value = sessions.value.filterNot { it.id == sessionId }
        bySession.remove(sessionId)
    }
}

/**
 * Domain boundary for the UI. Streaming stays hot (Flow<String> tokens),
 * persistence stays cold until collected.
 */
interface ChatRepository {
    /** Unified token stream for the given model + history. */
    fun streamReply(
        model: AiModel,
        history: List<ChatMessage>,
        systemPrompt: String? = null
    ): Flow<String>

    fun observeSessions(): Flow<List<ChatSession>>
    fun observeMessages(sessionId: String): Flow<List<ChatMessage>>
    suspend fun ensureSession(session: ChatSession)
    suspend fun persistTurn(sessionId: String, user: ChatMessage, assistant: ChatMessage)
}

class OfflineFirstChatRepository(
    private val llm: KtorLlmStreamingClient,
    private val storage: ChatStorage
) : ChatRepository {

    override fun streamReply(
        model: AiModel,
        history: List<ChatMessage>,
        systemPrompt: String?
    ): Flow<String> = llm.streamChat(model, history, systemPrompt)

    override fun observeSessions(): Flow<List<ChatSession>> = storage.observeSessions()

    override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        storage.observeMessages(sessionId).map { list -> list.sortedBy { it.timestampEpochMillis } }

    override suspend fun ensureSession(session: ChatSession) = storage.upsertSession(session)

    override suspend fun persistTurn(sessionId: String, user: ChatMessage, assistant: ChatMessage) {
        storage.appendMessage(sessionId, user)
        storage.appendMessage(sessionId, assistant)
    }
}
