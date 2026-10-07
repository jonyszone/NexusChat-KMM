package com.example.nexuschat.domain.repository

import com.example.nexuschat.data.model.AiModel
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.data.model.ChatMode
import com.example.nexuschat.data.model.ChatRole
import com.example.nexuschat.data.model.LlmProvider
import com.example.nexuschat.data.network.KtorLlmStreamingClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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
    val mode: ChatMode = ChatMode.DEMO,
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

    /** Insert-or-update a session, preserving its original [ChatSession.createdAt]. */
    suspend fun upsertSession(session: ChatSession)
    suspend fun renameSession(sessionId: String, title: String)
    suspend fun deleteSession(sessionId: String)

    /** Delete stored messages only; the session row stays. */
    suspend fun clearMessages(sessionId: String)

    /**
     * Durable pre-request write: persist the user message and an assistant
     * placeholder, and update session model/prompt/timestamp, in one transaction.
     */
    suspend fun beginTurn(
        sessionId: String,
        user: ChatMessage,
        assistantPlaceholder: ChatMessage,
        modelId: String,
        systemPrompt: String?,
        updatedAt: Long
    )

    /** Idempotent message upsert by id; assigns a stable sequence on first insert. */
    suspend fun upsertMessage(sessionId: String, message: ChatMessage)

    /** Convert abandoned PENDING/STREAMING rows to INTERRUPTED after process death. */
    suspend fun markInterrupted(sessionId: String)
}

/** In-memory storage for previews / JVM unit tests without a SQL driver. */
class InMemoryChatStorage : ChatStorage {
    private val sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    private val bySession = mutableMapOf<String, MutableStateFlow<List<ChatMessage>>>()

    private fun messages(sessionId: String): MutableStateFlow<List<ChatMessage>> =
        bySession.getOrPut(sessionId) { MutableStateFlow(emptyList()) }

    override fun observeSessions(): Flow<List<ChatSession>> = sessions

    override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> = messages(sessionId)

    override suspend fun upsertSession(session: ChatSession) {
        sessions.value = (sessions.value.filterNot { it.id == session.id } + session)
            .sortedWith(compareByDescending<ChatSession> { it.updatedAt }.thenBy { it.id })
    }

    override suspend fun renameSession(sessionId: String, title: String) {
        sessions.value = sessions.value.map {
            if (it.id == sessionId) it.copy(title = title) else it
        }
    }

    override suspend fun deleteSession(sessionId: String) {
        sessions.value = sessions.value.filterNot { it.id == sessionId }
        bySession.remove(sessionId)
    }

    override suspend fun clearMessages(sessionId: String) {
        messages(sessionId).value = emptyList()
    }

    override suspend fun beginTurn(
        sessionId: String,
        user: ChatMessage,
        assistantPlaceholder: ChatMessage,
        modelId: String,
        systemPrompt: String?,
        updatedAt: Long
    ) {
        val current = messages(sessionId).value
        var seq = (current.maxOfOrNull { it.sequence } ?: 0L)
        val ordered = current.toMutableList()
        listOf(user, assistantPlaceholder).forEach { message ->
            if (ordered.none { it.id == message.id }) {
                seq += 1
                ordered += message.copy(sequence = seq)
            }
        }
        messages(sessionId).value = ordered
        sessions.value = sessions.value.map {
            if (it.id == sessionId) {
                it.copy(modelId = modelId, systemPrompt = systemPrompt, updatedAt = updatedAt)
            } else {
                it
            }
        }.sortedWith(compareByDescending<ChatSession> { it.updatedAt }.thenBy { it.id })
    }

    override suspend fun upsertMessage(sessionId: String, message: ChatMessage) {
        val current = messages(sessionId).value
        val existing = current.firstOrNull { it.id == message.id }
        messages(sessionId).value = if (existing == null) {
            val seq = (current.maxOfOrNull { it.sequence } ?: 0L) + 1
            current + message.copy(sequence = seq)
        } else {
            current.map { if (it.id == message.id) message.copy(sequence = existing.sequence) else it }
        }
    }

    override suspend fun markInterrupted(sessionId: String) {
        messages(sessionId).value = messages(sessionId).value.map { message ->
            if (message.state.isActive) {
                message.copy(state = com.example.nexuschat.data.model.TurnState.INTERRUPTED)
            } else {
                message
            }
        }
    }
}

/**
 * Domain boundary for the UI. Streaming stays hot (Flow<String> tokens),
 * persistence stays cold until collected.
 */
interface ChatRepository {
    /** Explicit provenance for all reads/writes routed through this repository. */
    val mode: ChatMode

    fun streamReply(
        model: AiModel,
        history: List<ChatMessage>,
        systemPrompt: String? = null
    ): Flow<String>

    fun observeSessions(): Flow<List<ChatSession>>
    fun observeMessages(sessionId: String): Flow<List<ChatMessage>>

    suspend fun createSession(session: ChatSession)
    suspend fun renameSession(sessionId: String, title: String)
    suspend fun deleteSession(sessionId: String)
    suspend fun clearMessages(sessionId: String)

    suspend fun beginTurn(
        sessionId: String,
        user: ChatMessage,
        assistantPlaceholder: ChatMessage,
        model: AiModel,
        systemPrompt: String?,
        updatedAt: Long
    )

    suspend fun upsertMessage(sessionId: String, message: ChatMessage)
    suspend fun recoverInterrupted(sessionId: String)
}

class OfflineFirstChatRepository(
    private val llm: KtorLlmStreamingClient,
    private val storage: ChatStorage,
    override val mode: ChatMode = ChatMode.BYOK
) : ChatRepository {

    override fun streamReply(
        model: AiModel,
        history: List<ChatMessage>,
        systemPrompt: String?
    ): Flow<String> = llm.streamChat(model, history, systemPrompt)

    override fun observeSessions(): Flow<List<ChatSession>> = storage.observeSessions()

    override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        storage.observeMessages(sessionId)

    override suspend fun createSession(session: ChatSession) = storage.upsertSession(session)

    override suspend fun renameSession(sessionId: String, title: String) =
        storage.renameSession(sessionId, title)

    override suspend fun deleteSession(sessionId: String) = storage.deleteSession(sessionId)

    override suspend fun clearMessages(sessionId: String) = storage.clearMessages(sessionId)

    override suspend fun beginTurn(
        sessionId: String,
        user: ChatMessage,
        assistantPlaceholder: ChatMessage,
        model: AiModel,
        systemPrompt: String?,
        updatedAt: Long
    ) = storage.beginTurn(
        sessionId = sessionId,
        user = user,
        assistantPlaceholder = assistantPlaceholder,
        modelId = model.id,
        systemPrompt = systemPrompt,
        updatedAt = updatedAt
    )

    override suspend fun upsertMessage(sessionId: String, message: ChatMessage) =
        storage.upsertMessage(sessionId, message)

    override suspend fun recoverInterrupted(sessionId: String) = storage.markInterrupted(sessionId)
}

/** Explicit, persisted Demo vs BYOK selection. Never falls back silently. */
interface ChatModeStore {
    val mode: StateFlow<ChatMode>
    suspend fun setMode(mode: ChatMode)
}

class InMemoryChatModeStore(initial: ChatMode = ChatMode.DEMO) : ChatModeStore {
    private val _mode = MutableStateFlow(initial)
    override val mode: StateFlow<ChatMode> = _mode.asStateFlow()
    override suspend fun setMode(mode: ChatMode) {
        _mode.value = mode
    }
}

/**
 * Routes every read and write to exactly one backend, chosen by the explicit
 * [ChatModeStore] value. DEMO never touches credentials and BYOK never falls
 * back to seeded demo content (an empty BYOK database is a true empty state).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModeAwareChatRepository(
    private val modeStore: ChatModeStore,
    private val real: ChatRepository,
    private val demo: ChatRepository
) : ChatRepository {

    override val mode: ChatMode get() = modeStore.mode.value

    private fun active(mode: ChatMode): ChatRepository = if (mode == ChatMode.DEMO) demo else real

    override fun streamReply(
        model: AiModel,
        history: List<ChatMessage>,
        systemPrompt: String?
    ): Flow<String> = modeStore.mode.flatMapLatest { mode ->
        active(mode).streamReply(model, history, systemPrompt)
    }

    override fun observeSessions(): Flow<List<ChatSession>> =
        modeStore.mode.flatMapLatest { mode -> active(mode).observeSessions() }

    override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        modeStore.mode.flatMapLatest { mode -> active(mode).observeMessages(sessionId) }

    override suspend fun createSession(session: ChatSession) =
        active(modeStore.mode.value).createSession(session)

    override suspend fun renameSession(sessionId: String, title: String) =
        active(modeStore.mode.value).renameSession(sessionId, title)

    override suspend fun deleteSession(sessionId: String) =
        active(modeStore.mode.value).deleteSession(sessionId)

    override suspend fun clearMessages(sessionId: String) =
        active(modeStore.mode.value).clearMessages(sessionId)

    override suspend fun beginTurn(
        sessionId: String,
        user: ChatMessage,
        assistantPlaceholder: ChatMessage,
        model: AiModel,
        systemPrompt: String?,
        updatedAt: Long
    ) = active(modeStore.mode.value).beginTurn(
        sessionId, user, assistantPlaceholder, model, systemPrompt, updatedAt
    )

    override suspend fun upsertMessage(sessionId: String, message: ChatMessage) =
        active(modeStore.mode.value).upsertMessage(sessionId, message)

    override suspend fun recoverInterrupted(sessionId: String) =
        active(modeStore.mode.value).recoverInterrupted(sessionId)
}
