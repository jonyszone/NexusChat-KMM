package com.example.nexuschat.domain.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.nexuschat.data.model.ChatMode
import com.example.nexuschat.data.model.TurnState
import com.example.nexuschat.db.NexusChatDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Upgrade safety for the v1 -> v2 schema. The baseline DDL below is the exact
 * `chat_session` / `chat_message` definition committed at `0f3a8bd`; the test
 * builds that database, seeds it, runs the real generated migration and then
 * asserts nothing was lost and the new columns behave.
 */
class ChatMigrationTest {

    private val v1Schema = listOf(
        """
        CREATE TABLE chat_session (
            id TEXT NOT NULL PRIMARY KEY,
            title TEXT NOT NULL,
            model_id TEXT NOT NULL,
            system_prompt TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE chat_message (
            id TEXT NOT NULL PRIMARY KEY,
            session_id TEXT NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            timestamp_epoch_millis INTEGER NOT NULL,
            model_id TEXT
        )
        """.trimIndent()
    )

    private fun v1Database(): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        v1Schema.forEach { driver.execute(null, it, 0) }
        driver.execute(
            null,
            """
            INSERT INTO chat_session(id, title, model_id, system_prompt, created_at, updated_at)
            VALUES ('legacy-session', 'Old chat', 'gpt-4o-mini', 'legacy prompt', 100, 200)
            """.trimIndent(),
            0
        )
        driver.execute(
            null,
            """
            INSERT INTO chat_message(id, session_id, role, content, timestamp_epoch_millis, model_id)
            VALUES ('legacy-user', 'legacy-session', 'USER', 'old question', 150, NULL)
            """.trimIndent(),
            0
        )
        driver.execute(
            null,
            """
            INSERT INTO chat_message(id, session_id, role, content, timestamp_epoch_millis, model_id)
            VALUES ('legacy-assistant', 'legacy-session', 'ASSISTANT', 'old answer', 160, 'gpt-4o-mini')
            """.trimIndent(),
            0
        )
        return driver
    }

    @Test
    fun migrating_v1_to_v2_preserves_sessions_and_messages() = runBlocking {
        val driver = v1Database()
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)

        NexusChatDatabase.Schema.migrate(driver, 1, 2)

        val storage = SqlDelightChatStorage(NexusChatDatabase(driver))
        val session = storage.observeSessions().first().single()
        assertEquals("legacy-session", session.id)
        assertEquals("Old chat", session.title)
        assertEquals("legacy prompt", session.systemPrompt)
        assertEquals(100L, session.createdAt)
        assertEquals(200L, session.updatedAt)
        assertEquals(ChatMode.DEMO, session.mode) // default added by the migration

        val messages = storage.observeMessages("legacy-session").first()
        assertEquals(listOf("legacy-user", "legacy-assistant"), messages.map { it.id })
        assertEquals("old question", messages[0].content)
        assertEquals("old answer", messages[1].content)
        assertEquals(TurnState.COMPLETED, messages[1].state)
        // Sequence was seeded from the original timestamp so ordering is preserved.
        assertTrue(messages[0].sequence < messages[1].sequence)
        driver.close()
    }

    @Test
    fun migrated_database_accepts_new_turn_state_rows() = runBlocking {
        val driver = v1Database()
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        NexusChatDatabase.Schema.migrate(driver, 1, 2)

        val database = NexusChatDatabase(driver)
        val storage = SqlDelightChatStorage(database)
        // Exercises the new columns and the enforced foreign key end to end.
        storage.upsertMessage("legacy-session", com.example.nexuschat.data.model.ChatMessage.user("new", id = "new-1"))

        val ids = storage.observeMessages("legacy-session").first().map { it.id }
        assertEquals(listOf("legacy-user", "legacy-assistant", "new-1"), ids)
        driver.close()
    }

    @Test
    fun schema_version_is_three() {
        assertEquals(3L, NexusChatDatabase.Schema.version)
    }

    @Test
    fun migrating_v1_to_current_keeps_ai_history_and_adds_account_scoped_messaging() = runBlocking {
        val driver = v1Database()
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        NexusChatDatabase.Schema.migrate(driver, 1, NexusChatDatabase.Schema.version)
        val database = NexusChatDatabase(driver)
        assertEquals(2, SqlDelightChatStorage(database).observeMessages("legacy-session").first().size)
        val messaging = com.example.nexuschat.domain.messenger.SqlDelightMessengerStorage(database)
        val identity = com.example.nexuschat.domain.messenger.MessengerIdentity("https://chat.test", "alice")
        messaging.saveConversations(identity, listOf(com.example.nexuschat.domain.messenger.BackendConversation("conversation", setOf("alice", "bob"))))
        assertEquals("conversation", messaging.conversations(identity).single().id)
        driver.close()
    }
}
