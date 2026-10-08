package com.example.nexuschat.presentation

import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.nexuschat.db.NexusChatDatabase
import com.example.nexuschat.domain.messenger.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MessengerViewModelTest {
    private val alice = MessengerSession("https://chat.test", "alice", "session-a", "private-token", 99999999)
    private val conversation = BackendConversation("c", setOf("alice", "bob"))
    private val viewModels = mutableListOf<MessengerViewModel>()
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var storage: SqlDelightMessengerStorage

    private class SessionStore(var value: MessengerSession? = null) : MessengerSessionStore {
        override suspend fun load() = value
        override suspend fun save(session: MessengerSession) { value = session }
        override suspend fun clear() { value = null }
    }

    private inner class Api : AuthenticatedMessagingApi {
        var authenticated = alice
        var sendUnavailable = false
        var historyUnavailable = false
        var listUnavailable = false
        var rejectSession = false
        var verifies = 0
        var acceptThenLoseResponse = false
        val sendKeys = mutableListOf<String>()
        val messages = mutableListOf<WireMessage>()
        val acknowledgements = mutableListOf<Long>()
        override suspend fun authenticate(serverUrl: String, email: String, password: String, register: Boolean) = authenticated
        override suspend fun verify(session: MessengerSession) { verifies++; if (rejectSession) throw MessagingHttpException(401) }
        override suspend fun revoke(session: MessengerSession) = Unit
        override suspend fun conversations(session: MessengerSession): List<BackendConversation> {
            if (listUnavailable) error("private-token raw server failure")
            return listOf(conversation)
        }
        override suspend fun createConversation(session: MessengerSession, peerId: String) = conversation
        override suspend fun send(session: MessengerSession, conversationId: String, key: String, body: String): WireMessage {
            sendKeys += key
            if (sendUnavailable) error("private-token raw server failure")
            val accepted = messages.firstOrNull { it.idempotencyKey == key && it.senderId == session.accountId }
                ?: WireMessage("m-${messages.size + 1}", conversationId, session.accountId, messages.size.toLong() + 1, key, body).also(messages::add)
            if (acceptThenLoseResponse) { acceptThenLoseResponse = false; error("response lost") }
            return accepted
        }
        override suspend fun history(session: MessengerSession, conversationId: String, after: Long): List<WireMessage> {
            if (historyUnavailable) error("offline")
            return messages.filter { it.sequence > after }
        }
        override suspend fun acknowledge(session: MessengerSession, conversationId: String, sequence: Long): Long { acknowledgements += sequence; return sequence }
    }

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        NexusChatDatabase.Schema.create(driver)
        storage = SqlDelightMessengerStorage(NexusChatDatabase(driver))
    }

    @AfterTest
    fun cleanup() {
        viewModels.forEach { it.viewModelScope.cancel() }
        driver.close()
        Dispatchers.resetMain()
    }

    private fun model(api: Api, sessions: SessionStore) = MessengerViewModel(api, sessions, storage, clock = { 1000 }, key = { "stable-key" }).also(viewModels::add)
    private suspend fun idle(vm: MessengerViewModel) = withTimeout(5_000) { vm.ui.first { !it.restoring && !it.busy } }
    private suspend fun connected(api: Api, sessions: SessionStore): MessengerViewModel {
        val vm = model(api, sessions)
        idle(vm)
        vm.authenticate(alice.serverUrl, "alice@example.test", "password", false)
        idle(vm)
        vm.selectConversation("c")
        idle(vm)
        return vm
    }

    @Test
    fun failed_send_is_durable_and_retry_uses_the_original_key() = runBlocking {
        val api = Api(); val sessions = SessionStore()
        val vm = connected(api, sessions)
        api.sendUnavailable = true
        vm.setDraft("hello"); vm.send()
        val failed = idle(vm)
        assertEquals("", failed.draft)
        assertEquals("FAILED", failed.pending.single().state)
        assertEquals("hello", storage.outbox(alice.identity).single().body)
        assertFalse(failed.error.orEmpty().contains("private-token"))
        api.sendUnavailable = false
        vm.retry("stable-key")
        val retried = idle(vm)
        assertEquals(listOf("stable-key", "stable-key"), api.sendKeys)
        assertEquals("hello", retried.messages.single().body)
        assertTrue(retried.pending.isEmpty())
        assertEquals(1L, storage.conversations(alice.identity).single().cursor)
        assertEquals(1L, api.acknowledgements.last())
    }

    @Test
    fun lost_send_response_is_reconciled_from_history_without_duplicate_sending() = runBlocking {
        val api = Api(); val vm = connected(api, SessionStore())
        api.acceptThenLoseResponse = true; api.historyUnavailable = true
        vm.setDraft("hello"); vm.send()
        assertEquals("FAILED", idle(vm).pending.single().state)
        assertEquals(1, api.messages.size)
        api.historyUnavailable = false
        vm.refresh()
        val restored = idle(vm)
        assertTrue(restored.pending.isEmpty())
        assertEquals(1, restored.messages.size)
        assertEquals(listOf("stable-key"), api.sendKeys)
    }

    @Test
    fun interrupted_outbox_is_recovered_when_a_saved_session_is_restored() = runBlocking {
        storage.saveConversations(alice.identity, listOf(conversation))
        storage.enqueue(alice.identity, PendingMessage("interrupted-key", "c", "recover", "SENDING"))
        val api = Api(); val vm = model(api, SessionStore(alice))
        assertTrue(idle(vm).signedIn)
        assertEquals(listOf("interrupted-key"), api.sendKeys)
        assertTrue(storage.outbox(alice.identity).isEmpty())
        assertEquals("recover", storage.messages(alice.identity, "c").single().body)
    }

    @Test
    fun realtime_hints_sync_history_and_follow_foreground_and_logout() = runBlocking {
        val api = Api()
        val hints = Channel<Unit>(Channel.UNLIMITED)
        var connections = 0
        var closed = 0
        val updates = MessengerUpdates {
            flow {
                connections++
                try {
                    emit(Unit)
                    for (hint in hints) emit(hint)
                } finally { closed++ }
            }
        }
        val vm = MessengerViewModel(api, SessionStore(alice), storage, clock = { 1000 }, updates = updates).also(viewModels::add)
        idle(vm)
        vm.selectConversation("c"); idle(vm)
        vm.setForeground(true)
        withTimeout(5_000) { vm.ui.first { it.realtimeConnected && !it.syncing } }
        val received = WireMessage("incoming", "c", "bob", 1, "peer-key", "live reply")
        api.messages += received
        hints.send(Unit)
        withTimeout(5_000) { vm.ui.first { it.messages == listOf(received) && !it.syncing } }
        assertEquals(1L, storage.conversations(alice.identity).single().cursor)
        vm.setForeground(false)
        assertFalse(vm.ui.value.realtimeConnected)
        assertEquals(1, closed)
        api.messages += WireMessage("missed", "c", "bob", 2, "missed-key", "while backgrounded")
        vm.setForeground(true)
        withTimeout(5_000) { vm.ui.first { it.messages.size == 2 && !it.syncing } }
        assertEquals(2, connections)
        vm.logout()
        withTimeout(5_000) { vm.ui.first { !it.signedIn && !it.busy } }
        assertEquals(2, closed)
        assertFalse(vm.ui.value.realtimeConnected)
        assertTrue(vm.ui.value.messages.isEmpty())
        hints.close()
        Unit
    }

    @Test
    fun switching_accounts_clears_the_visible_timeline_even_when_sync_is_offline() = runBlocking {
        val api = Api(); val sessions = SessionStore(); val vm = connected(api, sessions)
        vm.setDraft("private history"); vm.send(); idle(vm)
        vm.logout()
        assertFalse(idle(vm).signedIn)
        assertNull(sessions.value)
        api.authenticated = alice.copy(accountId = "bob", accessToken = "bob-token")
        api.listUnavailable = true
        vm.authenticate(alice.serverUrl, "bob@example.test", "password", false)
        val bob = idle(vm)
        assertEquals("bob", bob.accountId)
        assertTrue(bob.messages.isEmpty() && bob.conversations.isEmpty() && bob.pending.isEmpty())
        assertEquals("private history", storage.messages(alice.identity, "c").single().body)
    }

    @Test
    fun revoked_and_expired_saved_sessions_cannot_keep_the_user_signed_in() = runBlocking {
        val api = Api(); val sessions = SessionStore(alice); api.rejectSession = true
        val vm = model(api, sessions)
        assertFalse(idle(vm).signedIn)
        assertNull(sessions.value)
        val expiredApi = Api(); val expired = SessionStore(alice.copy(expiresAt = 0))
        val expiredVm = model(expiredApi, expired)
        assertFalse(idle(expiredVm).signedIn)
        assertNull(expired.value)
        assertEquals(0, expiredApi.verifies)
    }
}
