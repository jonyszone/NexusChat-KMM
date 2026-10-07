package com.example.nexuschat.server

import com.example.nexuschat.server.domain.DevelopmentAccountRepository
import com.example.nexuschat.server.domain.FileAccountRepository
import com.example.nexuschat.server.domain.InMemoryMessageRepository
import com.example.nexuschat.server.domain.SendCommand
import com.example.nexuschat.server.transport.ClientEnvelope
import com.example.nexuschat.server.transport.ServerEnvelope
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTest {
    private val json = Json { classDiscriminator = "type" }

    @Test fun fileAccountRepositorySurvivesRestartAndEnforcesFixtureMembership() {
        val file = kotlin.io.path.createTempFile("accounts", ".json").toFile().also { it.delete() }
        try {
            val first = FileAccountRepository(file, DevelopmentAccountRepository.snapshot())
            assertEquals("alice", first.account("alice")?.id)
            val restarted = FileAccountRepository(file)
            assertEquals(listOf("bob"), restarted.contacts("alice").map { it.id })
            assertTrue(restarted.isMember("alice", "c"))
            assertTrue(!restarted.isMember("mallory", "c"))
        } finally { file.delete() }
    }

    @Test fun scriptedAccountContactAndDirectConversationEndpointsUseFixtureMembership() = testApplication {
        application { module() }
        val wsClient = createClient { install(WebSockets) }
        val alice = client.get("/dev/v1/account") { header("X-Dev-User-Id", "alice") }
        assertEquals(200, alice.status.value)
        val contacts = client.get("/dev/v1/contacts") { header("X-Dev-User-Id", "alice") }
        assertTrue(contacts.bodyAsText().contains("bob"))
        val conversation = client.get("/dev/v1/conversations/direct/bob") { header("X-Dev-User-Id", "alice") }
        assertTrue(conversation.bodyAsText().contains("c"))
        val socket = wsClient.webSocketSession("/v1/realtime") { header("X-Dev-User-Id", "mallory") }
        socket.send(json.encodeToString<ClientEnvelope>(ClientEnvelope.SendMessage("c", "cm", "ik", "hello")))
        val error = json.decodeFromString<ServerEnvelope>((socket.incoming.receive() as Frame.Text).data.decodeToString()) as ServerEnvelope.Error
        assertEquals("MESSAGE_REJECTED", error.code)
    }

    @Test fun envelopeRoundTrip() {
        val value: ClientEnvelope = ClientEnvelope.CallSignal("c", "call", "offer", buildJsonObject { put("sdp", JsonPrimitive("x")) })
        assertEquals(value, json.decodeFromString<ClientEnvelope>(json.encodeToString(value)))
        val ack: ServerEnvelope = ServerEnvelope.Error("X", "no", "client", "idem")
        assertEquals(ack, json.decodeFromString<ServerEnvelope>(json.encodeToString(ack)))
        assertEquals(1, value.version)
    }

    @Test fun fileRepositorySurvivesRestart() = runTest {
        val file = kotlin.io.path.createTempFile("nexus", ".json").toFile(); file.delete()
        val members = mapOf("c" to setOf("alice"))
        val first = com.example.nexuschat.server.domain.FileMessageRepository(file, members)
        val command = SendCommand("c", "m", "key", "hello")
        val accepted = first.accept("alice", command)
        val restarted = com.example.nexuschat.server.domain.FileMessageRepository(file, members)
        assertTrue(restarted.accept("alice", command).duplicate)
        assertEquals(accepted.serverMessageId, restarted.history("alice", "c", 0).single().serverMessageId)
        file.delete()
    }

    @Test fun duplicateSendIsIdempotent() = runTest {
        val repo = InMemoryMessageRepository()
        val command = SendCommand("c", "client", "idem", "hello")
        val first = repo.accept("alice", command); val second = repo.accept("alice", command)
        assertEquals(first.serverMessageId, second.serverMessageId)
        assertFalse(first.duplicate); assertTrue(second.duplicate)
    }

    @Test fun nonMemberCannotSendAndHistoryUsesCursor() = runTest {
        val repo = InMemoryMessageRepository(mapOf("c" to setOf("alice", "bob")))
        val first = repo.accept("alice", SendCommand("c", "m1", "i1", "hello"))
        val second = repo.accept("bob", SendCommand("c", "m2", "i2", "there"))
        assertEquals(1L, first.sequence)
        assertEquals(2L, second.sequence)
        assertEquals(listOf("m2"), repo.history("bob", "c", afterSequence = 1).map { it.clientMessageId })
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            repo.accept("mallory", SendCommand("c", "m3", "i3", "intrusion"))
        }
    }

    @Test fun healthAndWebSocketAckRouting() = testApplication {
        application { module() }
        val response = client.get("/healthz")
        assertEquals(200, response.status.value); assertEquals("ok", response.bodyAsText())
        val wsClient = createClient { install(WebSockets) }
        val socket = wsClient.webSocketSession("/v1/realtime") { header("X-Dev-User-Id", "alice") }
        socket.send(json.encodeToString<ClientEnvelope>(ClientEnvelope.SendMessage("c", "cm", "ik", "hello")))
        val frame = socket.incoming.receive() as Frame.Text
        val ack = json.decodeFromString<ServerEnvelope>(frame.data.decodeToString()) as ServerEnvelope.MessageAck
        assertEquals("cm", ack.clientMessageId)
        socket.send(json.encodeToString<ClientEnvelope>(ClientEnvelope.History("c", 0)))
        val history = json.decodeFromString<ServerEnvelope>((socket.incoming.receive() as Frame.Text).data.decodeToString()) as ServerEnvelope.History
        assertEquals(listOf(1L), history.messages.map { it.sequence })
        val outsider = wsClient.webSocketSession("/v1/realtime") { header("X-Dev-User-Id", "mallory") }
        outsider.send(json.encodeToString<ClientEnvelope>(ClientEnvelope.SendMessage("c", "bad", "bad-key", "no")))
        val error = json.decodeFromString<ServerEnvelope>((outsider.incoming.receive() as Frame.Text).data.decodeToString()) as ServerEnvelope.Error
        assertEquals("MESSAGE_REJECTED", error.code)
        assertEquals("bad-key", error.idempotencyKey)
        // testApplication closes its WebSocket sessions at the end of the test.
    }
}
