package com.example.nexuschat.domain.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.nexuschat.data.model.AvailableModels
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.data.model.ChatMode
import com.example.nexuschat.data.model.ChatRole
import com.example.nexuschat.data.model.LlmProvider
import com.example.nexuschat.data.model.TurnState
import com.example.nexuschat.db.NexusChatDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real file-backed persistence tests: the driver and storage are closed and a
 * brand new driver is opened on the same file, so durability is actually
 * exercised rather than tested against one in-memory instance.
 */
class SqlDelightChatStorageTest {

    private val tempFiles = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.delete() }
    }

    private fun newFile(): File =
        File.createTempFile("nexuschat-test", ".db").also {
            it.delete()
            tempFiles += it
        }

    private fun open(file: File): Pair<JdbcSqliteDriver, NexusChatDatabase> {
        // JdbcSqliteDriver uses a per-thread connection for file paths, so the
        // pragma must be supplied as a connection property to apply everywhere.
        val properties = java.util.Properties().apply { setProperty("foreign_keys", "true") }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", properties)
        return driver to NexusChatDatabase(driver)
    }

    private fun session(id: String, updatedAt: Long = 2L) = ChatSession(
        id = id,
        title = "Chat $id",
        modelId = "gpt-4o-mini",
        systemPrompt = "be brief",
        mode = ChatMode.BYOK,
        createdAt = 1L,
        updatedAt = updatedAt
    )

    @Test
    fun messages_and_session_survive_a_real_file_backed_reopen() = runBlocking {
        val file = newFile()
        val (driver, database) = open(file)
        NexusChatDatabase.Schema.create(driver)
        val storage = SqlDelightChatStorage(database)

        storage.upsertSession(session("s1"))
        val user = ChatMessage.user("hello", id = "u1", turnId = "t1", attemptId = "at1", timestamp = 10L)
        val assistant = ChatMessage.assistant(
            "hi there",
            modelId = "gpt-4o-mini",
            id = "m1",
            turnId = "t1",
            attemptId = "at1",
            state = TurnState.COMPLETED,
            provider = LlmProvider.OPENAI,
            timestamp = 11L
        )
        storage.beginTurn("s1", user, assistant, AvailableModels.Gpt4oMini.id, "be brief", 11L)

        // Close everything, then reopen on the same file with a fresh driver.
        driver.close()
        val (driver2, database2) = open(file)
        val reopened = SqlDelightChatStorage(database2)

        val messages = reopened.observeMessages("s1").first()
        assertEquals(listOf("u1", "m1"), messages.map { it.id })
        assertEquals(listOf(1L, 2L), messages.map { it.sequence })
        assertEquals(TurnState.COMPLETED, messages[1].state)
        assertEquals(LlmProvider.OPENAI, messages[1].provider)
        assertEquals("t1", messages[1].turnId)

        val persistedSession = reopened.observeSessions().first().single()
        assertEquals("be brief", persistedSession.systemPrompt)
        assertEquals(11L, persistedSession.updatedAt)
        assertEquals(ChatMode.BYOK, persistedSession.mode)
        driver2.close()
    }

    @Test
    fun ordering_is_stable_when_timestamps_tie() = runBlocking {
        val file = newFile()
        val (driver, database) = open(file)
        NexusChatDatabase.Schema.create(driver)
        val storage = SqlDelightChatStorage(database)
        storage.upsertSession(session("s1"))

        // Same timestamp for all three; the sequence tie-breaker must keep insertion order.
        listOf("a", "b", "c").forEach { id ->
            storage.upsertMessage(
                "s1",
                ChatMessage(id = id, role = ChatRole.USER, content = id, timestampEpochMillis = 5L)
            )
        }

        assertEquals(listOf("a", "b", "c"), storage.observeMessages("s1").first().map { it.id })
        driver.close()
    }

    @Test
    fun repeated_persistence_with_same_ids_is_idempotent() = runBlocking {
        val file = newFile()
        val (driver, database) = open(file)
        NexusChatDatabase.Schema.create(driver)
        val storage = SqlDelightChatStorage(database)
        storage.upsertSession(session("s1"))

        val user = ChatMessage.user("hello", id = "u1", turnId = "t1", attemptId = "at1")
        val placeholder = ChatMessage.assistant(
            "", modelId = "gpt-4o-mini", id = "m1", turnId = "t1", attemptId = "at1", state = TurnState.PENDING
        )
        storage.beginTurn("s1", user, placeholder, AvailableModels.Gpt4oMini.id, null, 10L)
        // Same call again (e.g. a retried pre-request write).
        storage.beginTurn("s1", user, placeholder, AvailableModels.Gpt4oMini.id, null, 10L)

        assertEquals(2, storage.observeMessages("s1").first().size)
        // Settle the placeholder twice; the row is updated in place, not duplicated.
        storage.upsertMessage("s1", placeholder.copy(content = "done", state = TurnState.COMPLETED))
        storage.upsertMessage("s1", placeholder.copy(content = "done", state = TurnState.COMPLETED))

        val messages = storage.observeMessages("s1").first()
        assertEquals(2, messages.size)
        assertEquals("done", messages[1].content)
        assertEquals(TurnState.COMPLETED, messages[1].state)
        driver.close()
    }

    @Test
    fun foreign_key_rejects_a_message_without_a_session() = runBlocking {
        val file = newFile()
        val (driver, database) = open(file)
        NexusChatDatabase.Schema.create(driver)
        val storage = SqlDelightChatStorage(database)

        assertFailsWith<Exception> {
            storage.upsertMessage(
                "missing-session",
                ChatMessage.user("orphan", id = "orphan-1")
            )
        }
        driver.close()
    }

    @Test
    fun mark_interrupted_converts_only_active_turns() = runBlocking {
        val file = newFile()
        val (driver, database) = open(file)
        NexusChatDatabase.Schema.create(driver)
        val storage = SqlDelightChatStorage(database)
        storage.upsertSession(session("s1"))

        val user = ChatMessage.user("hello", id = "u1", turnId = "t1", attemptId = "at1")
        val placeholder = ChatMessage.assistant(
            "partial", modelId = "gpt-4o-mini", id = "m1", turnId = "t1", attemptId = "at1", state = TurnState.STREAMING
        )
        storage.beginTurn("s1", user, placeholder, AvailableModels.Gpt4oMini.id, null, 10L)

        storage.markInterrupted("s1")

        val messages = storage.observeMessages("s1").first()
        assertEquals(TurnState.COMPLETED, messages[0].state) // user stays completed
        assertEquals(TurnState.INTERRUPTED, messages[1].state)
        assertEquals("partial", messages[1].content) // partial text retained
        driver.close()
    }

    @Test
    fun clear_messages_keeps_the_session_row() = runBlocking {
        val file = newFile()
        val (driver, database) = open(file)
        NexusChatDatabase.Schema.create(driver)
        val storage = SqlDelightChatStorage(database)
        storage.upsertSession(session("s1"))
        storage.upsertMessage("s1", ChatMessage.user("hello", id = "u1"))

        storage.clearMessages("s1")

        assertTrue(storage.observeMessages("s1").first().isEmpty())
        assertEquals(listOf("s1"), storage.observeSessions().first().map { it.id })
        driver.close()
    }

    @Test
    fun deleting_a_session_removes_its_messages() = runBlocking {
        val file = newFile()
        val (driver, database) = open(file)
        NexusChatDatabase.Schema.create(driver)
        val storage = SqlDelightChatStorage(database)
        storage.upsertSession(session("s1"))
        storage.upsertMessage("s1", ChatMessage.user("hello", id = "u1"))

        storage.deleteSession("s1")

        assertTrue(storage.observeSessions().first().isEmpty())
        assertTrue(storage.observeMessages("s1").first().isEmpty())
        driver.close()
    }

    @Test
    fun upsert_session_preserves_original_created_at() = runBlocking {
        val file = newFile()
        val (driver, database) = open(file)
        NexusChatDatabase.Schema.create(driver)
        val storage = SqlDelightChatStorage(database)

        storage.upsertSession(session("s1"))
        storage.upsertSession(session("s1", updatedAt = 99L).copy(title = "Renamed"))

        val stored = storage.observeSessions().first().single()
        assertEquals(1L, stored.createdAt) // untouched
        assertEquals("Renamed", stored.title)
        assertEquals(99L, stored.updatedAt)
        driver.close()
    }
}
