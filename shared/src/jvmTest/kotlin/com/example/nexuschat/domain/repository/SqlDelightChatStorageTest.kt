package com.example.nexuschat.domain.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.data.model.ChatRole
import com.example.nexuschat.db.NexusChatDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlDelightChatStorageTest {
    @Test
    fun session_and_messages_survive_storage_recreation() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NexusChatDatabase.Schema.create(driver)
        val storage = SqlDelightChatStorage(NexusChatDatabase(driver))
        val session = ChatSession(
            id = "session-1",
            title = "Test chat",
            modelId = "gpt-4o-mini",
            createdAt = 1L,
            updatedAt = 2L
        )
        val message = ChatMessage(
            id = "message-1",
            role = ChatRole.USER,
            content = "Persist me",
            timestampEpochMillis = 3L
        )

        storage.upsertSession(session)
        storage.appendMessage(session.id, message)

        assertEquals(listOf(session), storage.observeSessions().first())
        assertEquals(listOf(message), storage.observeMessages(session.id).first())
    }
}
