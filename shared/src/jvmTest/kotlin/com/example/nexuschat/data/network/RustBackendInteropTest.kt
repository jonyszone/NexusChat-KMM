package com.example.nexuschat.data.network

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.nexuschat.db.NexusChatDatabase
import com.example.nexuschat.domain.messenger.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.uuid.Uuid

class RustBackendInteropTest {
    private class Bridge(binary: String, directory: Path) : AutoCloseable {
        private val process = ProcessBuilder(binary, directory.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val input = process.outputStream.bufferedWriter()
        private val output = process.inputStream.bufferedReader()
        val http = HttpClient(MockEngine { request ->
            val response = withContext(Dispatchers.IO) {
                input.write(buildJsonObject {
                    put("method", request.method.value)
                    put("uri", request.url.encodedPath + request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty())
                    put("body", (request.body as? TextContent)?.text.orEmpty())
                    request.headers["Authorization"]?.let { put("authorization", it) }
                }.toString())
                input.newLine(); input.flush()
                Json.parseToJsonElement(checkNotNull(output.readLine()) { "Rust fixture exited unexpectedly" }).jsonObject
            }
            respond(response["body"]!!.jsonPrimitive.content, HttpStatusCode.fromValue(response["status"]!!.jsonPrimitive.int))
        })
        val api = RustMessagingApi(http)

        override fun close() {
            http.close()
            input.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor() }
            output.close()
        }
    }

    @Test
    fun shared_client_and_rust_routes_interoperate_across_restart_for_two_users() = runBlocking {
        val binary = System.getenv("NEXUS_RUST_FIXTURE_BIN")
        assumeTrue("Set NEXUS_RUST_FIXTURE_BIN to the built http_contract_fixture example", binary != null && File(binary).canExecute())
        val directory = Files.createTempDirectory("nexus-rust-interop-")
        var bridge: Bridge? = null
        var driver: JdbcSqliteDriver? = null
        try {
            bridge = Bridge(binary!!, directory)
            val first = bridge
            val alice = first.api.authenticate("https://rust.fixture", "alice@example.test", "long test password", true)
            val bob = first.api.authenticate("https://rust.fixture", "bob@example.test", "long test password", true)
            val outsider = first.api.authenticate("https://rust.fixture", "outsider@example.test", "long test password", true)
            val conversation = first.api.createConversation(alice, bob.accountId)
            assertEquals(conversation.id, first.api.conversations(bob).single().id)
            assertTrue(first.api.conversations(outsider).isEmpty())
            val key = Uuid.random().toString()
            val sent = first.api.send(alice, conversation.id, key, "hello from Kotlin")
            val reply = first.api.send(bob, conversation.id, Uuid.random().toString(), "reply from Bob")
            assertEquals(sent, first.api.send(alice, conversation.id, key, "hello from Kotlin"))
            assertEquals(409, assertFailsWith<MessagingHttpException> { first.api.send(alice, conversation.id, key, "conflicting retry") }.status)
            assertEquals(403, assertFailsWith<MessagingHttpException> { first.api.history(outsider, conversation.id, 0) }.status)
            val history = first.api.history(alice, conversation.id, 0)
            assertEquals(listOf(sent, reply), history)
            driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("client.db")}")
            NexusChatDatabase.Schema.create(driver)
            var storage = SqlDelightMessengerStorage(NexusChatDatabase(driver))
            storage.saveConversations(alice.identity, listOf(conversation))
            storage.mergeHistory(alice.identity, conversation.id, 0, history)
            assertEquals(2L, first.api.acknowledge(alice, conversation.id, 2))
            first.close(); bridge = null
            driver.close(); driver = null

            bridge = Bridge(binary, directory)
            val restarted = bridge
            restarted.api.verify(alice); restarted.api.verify(bob)
            assertEquals(alice.accountId, restarted.api.authenticate("https://rust.fixture", "alice@example.test", "long test password", false).accountId)
            assertEquals(conversation, restarted.api.conversations(bob).single())
            assertEquals(listOf(sent, reply), restarted.api.history(bob, conversation.id, 0))
            assertEquals(sent, restarted.api.send(alice, conversation.id, key, "hello from Kotlin"))
            assertEquals(2L, restarted.api.acknowledge(alice, conversation.id, 1))
            val third = restarted.api.send(bob, conversation.id, Uuid.random().toString(), "after server restart")
            assertEquals(3L, third.sequence)
            driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("client.db")}")
            storage = SqlDelightMessengerStorage(NexusChatDatabase(driver))
            val after = storage.conversations(alice.identity).single().cursor
            assertEquals(2L, after)
            storage.mergeHistory(alice.identity, conversation.id, after, restarted.api.history(alice, conversation.id, after))
            assertEquals(listOf(sent, reply, third), storage.messages(alice.identity, conversation.id))
            assertTrue(storage.messages(bob.identity, conversation.id).isEmpty())
            restarted.api.revoke(bob)
            assertEquals(401, assertFailsWith<MessagingHttpException> { restarted.api.verify(bob) }.status)
        } finally {
            driver?.close()
            bridge?.close()
            directory.toFile().deleteRecursively()
        }
    }
}
