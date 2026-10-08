package com.example.nexuschat.domain.messenger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RealtimeMessengerClientTest {
    private class FakeTransport : RealtimeTransport {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        override val incoming: Flow<String> = incomingChannel.receiveAsFlow()
        var connectedAs: String? = null
        var connectedSession: DeviceSession? = null
        var sent: MutableList<String> = mutableListOf()
        var respond: suspend (String) -> Unit = {}
        override suspend fun connect(devUserId: String) { connectedAs = devUserId }
        override suspend fun connect(session: DeviceSession) { connectedSession = session }
        override suspend fun send(text: String) { sent += text; respond(text) }
        override suspend fun disconnect() { connectedAs = null }
        suspend fun emit(value: String) { incomingChannel.send(value) }
    }

    private fun client(fake: FakeTransport, ids: Iterator<String> = listOf("request-1").iterator()) =
        DefaultRealtimeMessengerClient(fake, CoroutineScope(Dispatchers.Unconfined)) { ids.next() }

    @Test
    fun rust_wire_serialization_is_flattened_and_versioned() {
        val wire = Json.parseToJsonElement(encodeClientCommand("r1", ClientCommand.Ack("c", "a", 7))).jsonObject
        assertEquals(1, wire["version"]?.jsonPrimitive?.content?.toInt())
        assertEquals("r1", wire["id"]?.jsonPrimitive?.content)
        assertEquals("ACK", wire["type"]?.jsonPrimitive?.content)
        assertEquals(7, wire["sequence"]?.jsonPrimitive?.content?.toInt())
        assertTrue("payload" !in wire)
    }

    @Test
    fun send_correlates_ack_by_request_id_even_after_unrelated_response() = runBlocking {
        val fake = FakeTransport()
        fake.respond = { raw ->
            val id = Json.parseToJsonElement(raw).jsonObject["id"]!!.jsonPrimitive.content
            fake.emit("{\"version\":1,\"id\":\"other\",\"payload\":{\"type\":\"ACK\",\"acknowledgedThrough\":0}}")
            fake.emit("{\"version\":1,\"id\":\"$id\",\"payload\":{\"type\":\"SEND_ACK\",\"message\":{\"id\":\"m1\",\"conversationId\":\"c\",\"senderId\":\"a\",\"sequence\":1,\"idempotencyKey\":\"k\",\"body\":\"hi\"}}}")
        }
        val c = client(fake)
        c.connect("dev-a")
        assertEquals("m1", c.send("c", "a", "k", "hi").id)
        assertEquals("dev-a", fake.connectedAs)
    }

    @Test
    fun reconnect_catches_up_and_advances_cursor() = runBlocking {
        val fake = FakeTransport()
        fake.respond = { raw ->
            val root = Json.parseToJsonElement(raw).jsonObject
            val id = root["id"]!!.jsonPrimitive.content
            fake.emit("{\"version\":1,\"id\":\"$id\",\"payload\":{\"type\":\"HISTORY\",\"cursor\":2,\"messages\":[{\"id\":\"m3\",\"conversationId\":\"c\",\"senderId\":\"b\",\"sequence\":3,\"idempotencyKey\":\"k3\",\"body\":\"later\"}]}}")
        }
        val c = client(fake)
        c.connect("dev-a")
        assertEquals(listOf("m3"), c.reconnect("c", "a").map { it.id })
        assertEquals(3, c.cursors.value["c"])
    }

    @Test
    fun unauthorized_server_error_is_typed() = runBlocking {
        val fake = FakeTransport()
        fake.respond = { raw ->
            val id = Json.parseToJsonElement(raw).jsonObject["id"]!!.jsonPrimitive.content
            fake.emit("{\"version\":1,\"id\":\"$id\",\"payload\":{\"type\":\"ERROR\",\"code\":\"UNAUTHORIZED\",\"message\":\"dev identity rejected\"}}")
        }
        val c = client(fake)
        c.connect("unknown")
        assertFailsWith<RealtimeClientError.Unauthorized> { c.history("c", "unknown") }
        Unit
    }

    @Test
    fun authenticated_session_is_passed_to_transport_without_using_dev_identity() = runBlocking {
        val fake = FakeTransport()
        val session = DeviceSession("alice", "android-1", "access-token")
        val c = client(fake)

        c.connect(session)

        assertEquals(session, fake.connectedSession)
        assertEquals(null, fake.connectedAs)
        assertEquals(RealtimeConnectionState.CONNECTED, c.connectionState.value)
    }
}
