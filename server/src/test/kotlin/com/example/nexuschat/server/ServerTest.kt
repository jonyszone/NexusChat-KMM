package com.example.nexuschat.server

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

    @Test fun envelopeRoundTrip() {
        val value: ClientEnvelope = ClientEnvelope.CallSignal("c", "call", "offer", buildJsonObject { put("sdp", JsonPrimitive("x")) })
        assertEquals(value, json.decodeFromString<ClientEnvelope>(json.encodeToString(value)))
    }

    @Test fun duplicateSendIsIdempotent() = runTest {
        val repo = InMemoryMessageRepository()
        val command = SendCommand("c", "client", "idem", "hello")
        val first = repo.accept("u", command); val second = repo.accept("u", command)
        assertEquals(first.serverMessageId, second.serverMessageId)
        assertFalse(first.duplicate); assertTrue(second.duplicate)
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
    }
}
