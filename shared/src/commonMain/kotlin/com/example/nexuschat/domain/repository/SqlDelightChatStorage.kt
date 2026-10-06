package com.example.nexuschat.domain.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.data.model.ChatRole
import com.example.nexuschat.db.NexusChatDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** SQLDelight-backed persistent storage shared by Android and JVM targets. */
class SqlDelightChatStorage(
    private val database: NexusChatDatabase
) : ChatStorage {
    private val queries = database.chatQueries

    override fun observeSessions(): Flow<List<ChatSession>> =
        queries.selectSessions()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map { row -> row.toDomain() } }

    override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        queries.selectMessages(sessionId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map { row -> row.toDomain() } }

    override suspend fun upsertSession(session: ChatSession) {
        queries.upsertSession(
            id = session.id,
            title = session.title,
            model_id = session.modelId,
            system_prompt = session.systemPrompt,
            created_at = session.createdAt,
            updated_at = session.updatedAt
        )
    }

    override suspend fun replaceMessages(sessionId: String, messages: List<ChatMessage>) {
        database.transaction {
            queries.deleteMessages(sessionId)
            messages.forEach { message ->
                queries.insertMessage(
                    id = message.id,
                    session_id = sessionId,
                    role = message.role.name,
                    content = message.content,
                    timestamp_epoch_millis = message.timestampEpochMillis,
                    model_id = message.modelId
                )
            }
        }
    }

    override suspend fun appendMessage(sessionId: String, message: ChatMessage) {
        queries.insertMessage(
            id = message.id,
            session_id = sessionId,
            role = message.role.name,
            content = message.content,
            timestamp_epoch_millis = message.timestampEpochMillis,
            model_id = message.modelId
        )
    }

    override suspend fun deleteSession(sessionId: String) {
        database.transaction {
            queries.deleteMessages(sessionId)
            queries.deleteSession(sessionId)
        }
    }
}

private fun com.example.nexuschat.db.Chat_session.toDomain() = ChatSession(
    id = id,
    title = title,
    modelId = model_id,
    systemPrompt = system_prompt,
    createdAt = created_at,
    updatedAt = updated_at
)

private fun com.example.nexuschat.db.Chat_message.toDomain() = ChatMessage(
    id = id,
    role = runCatching { ChatRole.valueOf(role) }.getOrDefault(ChatRole.USER),
    content = content,
    timestampEpochMillis = timestamp_epoch_millis,
    modelId = model_id
)
