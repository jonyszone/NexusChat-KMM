package com.example.nexuschat.domain.messenger

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.nexuschat.db.NexusChatDatabase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class SqlDelightMessengerStorageTest {
    private val alice = MessengerIdentity("https://chat.test", "alice")
    private val bob = MessengerIdentity("https://chat.test", "bob")
    private val conversation = BackendConversation("c", setOf("alice", "bob"))
    private fun message(sequence: Long, sender: String = "bob", key: String = "key-$sequence") =
        WireMessage("m-$sequence", "c", sender, sequence, key, "body-$sequence")

    private fun database(driver: JdbcSqliteDriver): SqlDelightMessengerStorage {
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return SqlDelightMessengerStorage(NexusChatDatabase(driver))
    }

    @Test
    fun messages_outbox_and_cursor_survive_reopening_the_database() = runBlocking {
        val file = Files.createTempFile("nexus-messenger-", ".db")
        try {
            val first = JdbcSqliteDriver("jdbc:sqlite:$file")
            NexusChatDatabase.Schema.create(first)
            val storage = database(first)
            storage.saveConversations(alice, listOf(conversation))
            storage.mergeHistory(alice, "c", 0, listOf(message(1)))
            storage.enqueue(alice, PendingMessage("stable-key", "c", "retry me", "SENDING"))
            first.close()
            val second = JdbcSqliteDriver("jdbc:sqlite:$file")
            try {
                val restored = database(second)
                assertEquals(1L, restored.conversations(alice).single().cursor)
                assertEquals(listOf(message(1)), restored.messages(alice, "c"))
                restored.recover(alice)
                assertEquals(PendingMessage("stable-key", "c", "retry me", "PENDING"), restored.outbox(alice).single())
            } finally { second.close() }
        } finally { Files.deleteIfExists(file) }
    }

    @Test
    fun account_and_server_scopes_never_share_cached_history_or_outbox() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            NexusChatDatabase.Schema.create(driver)
            val storage = database(driver)
            storage.saveConversations(alice, listOf(conversation))
            storage.mergeHistory(alice, "c", 0, listOf(message(1)))
            storage.enqueue(alice, PendingMessage("k", "c", "private draft", "FAILED"))
            for (other in listOf(bob, alice.copy(serverUrl = "https://other.test"))) {
                assertTrue(storage.conversations(other).isEmpty())
                assertTrue(storage.messages(other, "c").isEmpty())
                assertTrue(storage.outbox(other).isEmpty())
            }
        } finally { driver.close() }
    }

    @Test
    fun send_ack_does_not_skip_earlier_inbound_history_and_reconciliation_is_idempotent() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            NexusChatDatabase.Schema.create(driver)
            val storage = database(driver)
            storage.saveConversations(alice, listOf(conversation))
            val own = message(3, "alice", "stable-key")
            storage.enqueue(alice, PendingMessage("stable-key", "c", own.body, "SENDING"))
            storage.accept(alice, own)
            assertEquals(0L, storage.conversations(alice).single().cursor)
            assertTrue(storage.outbox(alice).isEmpty())
            storage.mergeHistory(alice, "c", 0, listOf(message(1), message(2), own))
            assertEquals(listOf(1L,2L,3L), storage.messages(alice, "c").map { it.sequence })
            assertEquals(3L, storage.conversations(alice).single().cursor)
            storage.saveConversations(alice, listOf(conversation))
            assertEquals(3L, storage.conversations(alice).single().cursor)
        } finally { driver.close() }
    }

    @Test
    fun a_history_gap_rolls_back_the_entire_batch_and_cursor() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            NexusChatDatabase.Schema.create(driver)
            val storage = database(driver)
            storage.saveConversations(alice, listOf(conversation))
            assertFailsWith<MessagingProtocolException> { storage.mergeHistory(alice, "c", 0, listOf(message(1), message(3))) }
            assertTrue(storage.messages(alice, "c").isEmpty())
            assertEquals(0L, storage.conversations(alice).single().cursor)
        } finally { driver.close() }
    }

    @Test
    fun conflicting_history_cannot_replace_a_cached_message_or_discard_a_queued_send() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            NexusChatDatabase.Schema.create(driver)
            val storage = database(driver)
            storage.saveConversations(alice, listOf(conversation))
            val expected = message(1, "alice", "stable-key")
            storage.enqueue(alice, PendingMessage("stable-key", "c", expected.body, "PENDING"))
            assertFailsWith<MessagingProtocolException> { storage.mergeHistory(alice, "c", 0, listOf(expected.copy(body = "conflicting body"))) }
            assertEquals(expected.body, storage.outbox(alice).single().body)
            assertTrue(storage.messages(alice, "c").isEmpty())
            storage.accept(alice, expected)
            assertFailsWith<MessagingProtocolException> { storage.mergeHistory(alice, "c", 0, listOf(expected.copy(id = "wrong id"))) }
            assertEquals(listOf(expected), storage.messages(alice, "c"))
            assertEquals(0L, storage.conversations(alice).single().cursor)
        } finally { driver.close() }
    }
}
