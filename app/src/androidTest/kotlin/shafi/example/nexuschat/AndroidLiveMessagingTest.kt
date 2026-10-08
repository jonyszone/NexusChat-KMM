package shafi.example.nexuschat

import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.example.nexuschat.data.network.RustMessagingApi
import com.example.nexuschat.data.network.KtorMessengerUpdates
import com.example.nexuschat.db.NexusChatDatabase
import com.example.nexuschat.domain.messenger.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.KeyStore
import java.util.UUID

class AndroidLiveMessagingTest {
    @Test
    fun twoAccountsExchangeMessagesThroughTheRealNetwork() = runBlocking {
        val endpoint = InstrumentationRegistry.getArguments().getString("messagingServer")
        assumeTrue("Provide messagingServer for an isolated test backend", endpoint != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "messenger_network_test_${UUID.randomUUID()}"
        val databaseName = "$namespace.db"
        val http = testMessagingClient()
        val allowHttp = BuildConfig.DEBUG && endpoint!!.startsWith("http://")
        val api = RustMessagingApi(http, allowInsecureTransport = allowHttp)
        val sessions = AndroidMessengerSessionStore(context, namespace)
        var driver: AndroidSqliteDriver? = null
        var recipientFeed: ReceiveChannel<Unit>? = null
        var outsiderFeed: ReceiveChannel<Unit>? = null
        try {
            val password = "isolated network test password"
            val alice = api.authenticate(endpoint!!, "alice-$namespace@example.test", password, true)
            val bob = api.authenticate(endpoint, "bob-$namespace@example.test", password, true)
            val outsider = api.authenticate(endpoint, "outsider-$namespace@example.test", password, true)
            sessions.save(alice)
            val restored = checkNotNull(AndroidMessengerSessionStore(context, namespace).load())
            assertEquals(alice, restored)
            api.verify(restored)
            val updates = KtorMessengerUpdates(http, allowInsecureTransport = allowHttp)
            recipientFeed = updates.events(bob).produceIn(this)
            outsiderFeed = updates.events(outsider).produceIn(this)
            withTimeout(15_000) { recipientFeed.receive(); outsiderFeed.receive() }
            val conversation = api.createConversation(restored, bob.accountId)
            withTimeout(15_000) { recipientFeed.receive() }
            assertEquals(listOf(conversation), api.conversations(bob))
            assertTrue(api.conversations(outsider).isEmpty())
            val key = UUID.randomUUID().toString()
            val sent = api.send(restored, conversation.id, key, "hello from Android")
            withTimeout(15_000) { recipientFeed.receive() }
            assertEquals(sent, api.send(restored, conversation.id, key, "hello from Android"))
            expectStatus(409) { api.send(restored, conversation.id, key, "conflicting retry") }
            expectStatus(403) { api.history(outsider, conversation.id, 0) }
            expectStatus(403) { api.send(outsider, conversation.id, UUID.randomUUID().toString(), "not a member") }
            val reply = api.send(bob, conversation.id, UUID.randomUUID().toString(), "reply to Android")
            assertEquals(listOf(sent, reply), api.history(bob, conversation.id, 0))
            assertNull(withTimeoutOrNull(200) { outsiderFeed.receive() })
            recipientFeed.cancel()
            driver = AndroidSqliteDriver(NexusChatDatabase.Schema, context, databaseName)
            var storage = SqlDelightMessengerStorage(NexusChatDatabase(driver))
            storage.saveConversations(alice.identity, listOf(conversation))
            storage.mergeHistory(alice.identity, conversation.id, 0, api.history(restored, conversation.id, 0))
            assertEquals(2L, api.acknowledge(restored, conversation.id, 2))
            assertEquals(2L, api.acknowledge(restored, conversation.id, 1))
            driver.close()
            driver = AndroidSqliteDriver(NexusChatDatabase.Schema, context, databaseName)
            storage = SqlDelightMessengerStorage(NexusChatDatabase(driver))
            assertEquals(listOf(sent, reply), storage.messages(alice.identity, conversation.id))
            assertTrue(storage.messages(bob.identity, conversation.id).isEmpty())
            val third = api.send(bob, conversation.id, UUID.randomUUID().toString(), "after local reopen")
            recipientFeed = updates.events(bob).produceIn(this)
            withTimeout(15_000) { recipientFeed.receive() }
            val after = storage.conversations(alice.identity).single().cursor
            assertEquals(2L, after)
            storage.mergeHistory(alice.identity, conversation.id, after, api.history(restored, conversation.id, after))
            assertEquals(listOf(sent, reply, third), storage.messages(alice.identity, conversation.id))
            api.revoke(bob)
            expectStatus(401) { api.verify(bob) }
            api.send(restored, conversation.id, UUID.randomUUID().toString(), "after peer revocation")
            assertTrue(withTimeout(15_000) { recipientFeed.receiveCatching().isClosed })
            api.revoke(restored)
            api.revoke(outsider)
            sessions.clear()
            assertNull(sessions.load())
        } finally {
            recipientFeed?.cancel()
            outsiderFeed?.cancel()
            driver?.close()
            http.close()
            sessions.clear()
            context.deleteDatabase(databaseName)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(namespace) }
        }
    }

    private suspend fun expectStatus(expected: Int, action: suspend () -> Unit) {
        try {
            action()
            fail("Expected HTTP $expected")
        } catch (cause: MessagingHttpException) {
            assertEquals(expected, cause.status)
        }
    }
}
