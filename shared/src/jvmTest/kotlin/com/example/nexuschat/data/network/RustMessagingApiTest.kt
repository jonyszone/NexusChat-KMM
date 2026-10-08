package com.example.nexuschat.data.network

import com.example.nexuschat.domain.messenger.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class RustMessagingApiTest {
    private val alice = "11111111-1111-4111-8111-111111111111"
    private val bob = "22222222-2222-4222-8222-222222222222"
    private val conversation = "33333333-3333-4333-8333-333333333333"
    private val message = "44444444-4444-4444-8444-444444444444"
    private val key = "55555555-5555-4555-8555-555555555555"
    private val session = MessengerSession("https://chat.test", alice, key, "private-token", 9999999999)
    private fun envelope(payload: String, version: Int = 1) = """{"version":$version,"id":"$key","payload":$payload}"""
    private fun wire(body: String = "hello") = """{"id":"$message","conversation_id":"$conversation","sender_id":"$alice","sequence":1,"idempotency_key":"$key","body":"$body"}"""

    @Test
    fun registration_uses_rust_fields_and_preserves_password_exactly() = runBlocking {
        var captured: HttpRequestData? = null
        val http = HttpClient(MockEngine { request ->
            captured = request
            respond(envelope("""{"account_id":"$alice","session_id":"$key","access_token":"private-token","expires_at":9999999999}"""))
        })
        try {
            val result = RustMessagingApi(http).authenticate("https://chat.test/", " alice@example.test ", " spaced \"password\" ", true)
            assertEquals(session, result)
            val request = checkNotNull(captured)
            assertEquals("https://chat.test/v1/auth/register", request.url.toString())
            assertNull(request.headers["Authorization"])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("alice@example.test", body["email"]!!.jsonPrimitive.content)
            assertEquals(" spaced \"password\" ", body["password"]!!.jsonPrimitive.content)
            assertFalse(result.toString().contains("private-token"))
        } finally { http.close() }
    }

    @Test
    fun protected_send_uses_bearer_and_never_supplies_sender_identity() = runBlocking {
        var captured: HttpRequestData? = null
        val http = HttpClient(MockEngine { request -> captured = request; respond(envelope(wire())) })
        try {
            val result = RustMessagingApi(http).send(session, conversation, key, "hello")
            assertEquals(message, result.id)
            val request = checkNotNull(captured)
            assertEquals("Bearer private-token", request.headers["Authorization"])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(setOf("idempotency_key", "body"), body.keys)
            assertEquals(key, body["idempotency_key"]!!.jsonPrimitive.content)
            assertNull(request.headers["X-Dev-User-Id"])
        } finally { http.close() }
    }

    @Test
    fun conversation_listing_and_history_match_the_rust_routes() = runBlocking {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            assertEquals("Bearer private-token", request.headers["Authorization"])
            paths += request.url.toString()
            respond(if (request.url.encodedPath.endsWith("messages")) "[${wire()}]" else """[{"id":"$conversation","members":["$alice","$bob"]}]""")
        })
        try {
            val api = RustMessagingApi(http)
            assertEquals(setOf(alice,bob), api.conversations(session).single().members)
            assertEquals(message, api.history(session, conversation, 0).single().id)
            assertEquals("https://chat.test/v1/messaging/conversations/$conversation/messages?after=0", paths.last())
        } finally { http.close() }
    }

    @Test
    fun server_errors_are_typed_and_do_not_expose_response_bodies() = runBlocking {
        val http = HttpClient(MockEngine { respond("private-token and raw error", HttpStatusCode.Forbidden) })
        try {
            val error = assertFailsWith<MessagingHttpException> { RustMessagingApi(http).history(session, conversation, 0) }
            assertEquals(403, error.status)
            assertFalse(error.message!!.contains("private-token"))
        } finally { http.close() }
    }

    @Test
    fun insecure_and_credential_bearing_server_urls_are_rejected_before_network_access() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { calls++; respond("unexpected") })
        try {
            val api = RustMessagingApi(http)
            for (url in listOf("http://chat.test", "https://user:secret@chat.test", "https://chat.test?token=secret", "https://chat.test/path")) {
                assertFailsWith<IllegalArgumentException> { api.authenticate(url, "a", "b", false) }
            }
            assertEquals(0, calls)
            assertEquals("http://10.0.2.2:3000", RustMessagingApi(http, true).normalizeServer("http://10.0.2.2:3000/"))
        } finally { http.close() }
    }

    @Test
    fun malformed_or_mismatched_responses_cannot_be_accepted_as_messages() = runBlocking {
        val http = HttpClient(MockEngine { respond(envelope(wire("different"))) })
        try { assertFailsWith<MessagingProtocolException> { RustMessagingApi(http).send(session, conversation, key, "hello") }; Unit
        } finally { http.close() }
    }

    @Test
    fun direct_conversation_must_include_exactly_the_selected_recipient() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { calls++; respond(envelope("""{"id":"$conversation","members":["$alice","$message"]}""")) })
        try {
            assertFailsWith<IllegalArgumentException> { RustMessagingApi(http).createConversation(session, alice.uppercase()) }
            assertEquals(0, calls)
            assertFailsWith<MessagingProtocolException> { RustMessagingApi(http).createConversation(session, bob) }
            Unit
        } finally { http.close() }
    }

    @Test
    fun rust_realtime_format_uses_snake_case_and_decodes_acknowledgements() {
        val encoded = Json.parseToJsonElement(encodeClientCommand(key, ClientCommand.Send(conversation, alice, key, "hello"), RealtimeWireFormat.RUST)).jsonObject
        assertEquals(conversation, encoded["conversation_id"]!!.jsonPrimitive.content)
        assertTrue("sender_id" in encoded && "idempotency_key" in encoded)
        assertFalse("conversationId" in encoded)
        val decoded = decodeServerEnvelope(envelope("""{"type":"ACK","acknowledged_through":3}"""), RealtimeWireFormat.RUST)
        assertEquals(3L, (decoded.payload as ServerPayload.Ack).acknowledgedThrough)
    }
}
