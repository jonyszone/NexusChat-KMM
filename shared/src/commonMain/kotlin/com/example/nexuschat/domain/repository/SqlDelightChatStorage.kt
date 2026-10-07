package com.example.nexuschat.domain.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.data.model.ChatMode
import com.example.nexuschat.data.model.ChatRole
import com.example.nexuschat.data.model.LlmProvider
import com.example.nexuschat.data.model.TurnState
import com.example.nexuschat.db.Chat_message
import com.example.nexuschat.db.Chat_session
import com.example.nexuschat.db.NexusChatDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * SQLDelight-backed persistent storage shared by Android and JVM targets.
 *
 * All multi-row transitions run inside a single SQL transaction so a crash
 * between writes can never leave a half-persisted turn. Message writes are
 * idempotent by primary key, and per-session ordering is assigned here rather
 * than by the UI.
 */
class SqlDelightChatStorage(
    private val database: NexusChatDatabase
) : ChatStorage {
    private val queries = database.chatQueries

    override fun observeSessions(): Flow<List<ChatSession>> =
        queries.selectSessions()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map(Chat_session::toDomain) }

    override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        queries.selectMessages(sessionId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map(Chat_message::toDomain) }

    override suspend fun upsertSession(session: ChatSession) {
        database.transaction {
            // INSERT OR IGNORE keeps the original created_at/metadata if present,
            // then the UPDATE applies the new values without deleting the parent
            // row (which would cascade-delete messages under FK enforcement).
            queries.upsertSession(
                id = session.id,
                title = session.title,
                model_id = session.modelId,
                system_prompt = session.systemPrompt,
                mode = session.mode.name,
                created_at = session.createdAt,
                updated_at = session.updatedAt
            )
            queries.updateSession(
                title = session.title,
                model_id = session.modelId,
                system_prompt = session.systemPrompt,
                mode = session.mode.name,
                updated_at = session.updatedAt,
                id = session.id
            )
        }
    }

    override suspend fun renameSession(sessionId: String, title: String) {
        queries.renameSession(title = title, id = sessionId)
    }

    override suspend fun deleteSession(sessionId: String) {
        database.transaction {
            queries.deleteMessages(sessionId)
            queries.deleteSession(sessionId)
        }
    }

    override suspend fun clearMessages(sessionId: String) {
        queries.deleteMessages(sessionId)
    }

    override suspend fun beginTurn(
        sessionId: String,
        user: ChatMessage,
        assistantPlaceholder: ChatMessage,
        modelId: String,
        systemPrompt: String?,
        updatedAt: Long
    ) {
        database.transaction {
            writeMessage(sessionId, user)
            writeMessage(sessionId, assistantPlaceholder)
            queries.touchSession(
                model_id = modelId,
                system_prompt = systemPrompt,
                updated_at = updatedAt,
                id = sessionId
            )
        }
    }

    override suspend fun upsertMessage(sessionId: String, message: ChatMessage) {
        database.transaction { writeMessage(sessionId, message) }
    }

    override suspend fun markInterrupted(sessionId: String) {
        queries.markInterrupted(sessionId)
    }

    /** Idempotent by id; assigns the next per-session sequence on first insert. */
    private fun writeMessage(sessionId: String, message: ChatMessage) {
        val existing = queries.selectMessageById(message.id).executeAsOneOrNull()
        if (existing == null) {
            val nextSequence = queries.maxSequence(sessionId).executeAsOne() + 1
            queries.insertMessage(
                id = message.id,
                session_id = sessionId,
                role = message.role.name,
                content = message.content,
                timestamp_epoch_millis = message.timestampEpochMillis,
                model_id = message.modelId,
                turn_id = message.turnId,
                attempt_id = message.attemptId,
                sequence = nextSequence,
                state = message.state.name,
                provider = message.provider?.name,
                error_category = message.errorCategory
            )
        } else {
            queries.updateMessage(
                content = message.content,
                role = message.role.name,
                timestamp_epoch_millis = message.timestampEpochMillis,
                model_id = message.modelId,
                turn_id = message.turnId,
                attempt_id = message.attemptId,
                sequence = existing.sequence,
                state = message.state.name,
                provider = message.provider?.name,
                error_category = message.errorCategory,
                id = message.id
            )
        }
    }
}

internal fun Chat_session.toDomain() = ChatSession(
    id = id,
    title = title,
    modelId = model_id,
    systemPrompt = system_prompt,
    mode = runCatching { ChatMode.valueOf(mode) }.getOrDefault(ChatMode.DEMO),
    createdAt = created_at,
    updatedAt = updated_at
)

internal fun Chat_message.toDomain() = ChatMessage(
    id = id,
    role = runCatching { ChatRole.valueOf(role) }.getOrDefault(ChatRole.USER),
    content = content,
    timestampEpochMillis = timestamp_epoch_millis,
    modelId = model_id,
    turnId = turn_id,
    attemptId = attempt_id,
    sequence = sequence,
    state = runCatching { TurnState.valueOf(state) }.getOrDefault(TurnState.COMPLETED),
    provider = provider?.let { name -> LlmProvider.entries.firstOrNull { it.name == name } },
    errorCategory = error_category
)
