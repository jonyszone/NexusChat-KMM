package com.example.nexuschat.data.network

import com.example.nexuschat.data.model.AvailableModels
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.testutil.mockStreamingClient
import com.example.nexuschat.testutil.sse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic request/response contracts for every provider using MockEngine.
 * These assert the serialized body, URL and auth headers, plus the streaming
 * behaviours (framing, termination, error mapping) without any live calls.
 */
class ProviderContractTest {

    private fun client(
        sseBody: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        apiKey: String? = "secret-key",
        captured: ((HttpRequestData) -> Unit)? = null
    ) = mockStreamingClient(apiKey = apiKey, sseBody = sseBody, status = status, onRequest = captured)

    private fun bodyOf(request: HttpRequestData): String = (request.body as TextContent).text

    @Test
    fun openai_sends_bearer_token_system_and_history() = runBlocking {
        var request: HttpRequestData? = null
        val client = client(
            sseBody = sse(
                """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
                """data: {"choices":[{"delta":{"content":"lo"}}]}""",
                "data: [DONE]"
            ),
            captured = { request = it }
        )

        val tokens = client.streamChat(
            AvailableModels.Gpt4oMini,
            listOf(ChatMessage.user("hi", id = "u1")),
            systemPrompt = "be brief"
        ).toList()

        assertEquals(listOf("Hel", "lo"), tokens)
        assertEquals("Bearer secret-key", request!!.headers["Authorization"])
        assertEquals("https://api.openai.com/v1/chat/completions", request!!.url.toString())
        val body = bodyOf(request!!)
        assertTrue(body.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(body.contains("\"role\":\"system\"") && body.contains("be brief"))
        assertTrue(body.contains("\"role\":\"user\"") && body.contains("\"hi\""))
        assertTrue(body.contains("\"stream\":true"))
    }

    @Test
    fun openai_stream_without_done_marker_is_an_unexpected_eof() = runBlocking {
        val client = client(sseBody = sse("""data: {"choices":[{"delta":{"content":"partial"}}]}"""))

        val error = assertFailsWith<LlmStreamException> {
            client.streamChat(AvailableModels.Gpt4oMini, listOf(ChatMessage.user("hi"))).toList()
        }
        assertEquals("unexpected_eof", error.category)
    }

    @Test
    fun openai_error_event_is_a_stream_error() = runBlocking {
        val client = client(sseBody = sse("""data: {"error":{"message":"boom","type":"server_error"}}"""))

        val error = assertFailsWith<LlmStreamException> {
            client.streamChat(AvailableModels.Gpt4oMini, listOf(ChatMessage.user("hi"))).toList()
        }
        assertEquals("stream_error", error.category)
    }

    @Test
    fun openai_content_filter_finish_reason_is_a_refusal() = runBlocking {
        val client = client(
            sseBody = sse("""data: {"choices":[{"delta":{},"finish_reason":"content_filter"}]}""")
        )

        val error = assertFailsWith<LlmStreamException> {
            client.streamChat(AvailableModels.Gpt4oMini, listOf(ChatMessage.user("hi"))).toList()
        }
        assertEquals("refusal", error.category)
    }

    @Test
    fun deepseek_uses_its_own_endpoint_with_bearer_auth() = runBlocking {
        var request: HttpRequestData? = null
        val client = client(
            sseBody = sse("""data: {"choices":[{"delta":{"content":"ok"}}]}""", "data: [DONE]"),
            captured = { request = it }
        )

        client.streamChat(AvailableModels.DeepSeekChat, listOf(ChatMessage.user("hi"))).toList()

        assertEquals("https://api.deepseek.com/chat/completions", request!!.url.toString())
        assertEquals("Bearer secret-key", request!!.headers["Authorization"])
    }

    @Test
    fun anthropic_uses_top_level_system_and_message_stop() = runBlocking {
        var request: HttpRequestData? = null
        val client = client(
            sseBody = sse(
                "event: message_start\ndata: {\"type\":\"message_start\"}",
                "event: content_block_delta\ndata: {\"delta\":{\"text\":\"Hi\"}}",
                "event: message_delta\ndata: {\"delta\":{\"stop_reason\":\"end_turn\"}}",
                "event: message_stop\ndata: {\"type\":\"message_stop\"}"
            ),
            captured = { request = it }
        )

        val tokens = client.streamChat(
            AvailableModels.ClaudeHaiku,
            listOf(ChatMessage.user("hi")),
            systemPrompt = "be terse"
        ).toList()

        assertEquals(listOf("Hi"), tokens)
        assertEquals("secret-key", request!!.headers["x-api-key"])
        assertEquals("2023-06-01", request!!.headers["anthropic-version"])
        val body = bodyOf(request!!)
        assertTrue(body.contains("\"system\":\"be terse\""), "system must be a top-level field")
        assertFalse(body.contains("\"role\":\"system\""))
    }

    @Test
    fun anthropic_error_event_is_a_stream_error() = runBlocking {
        val client = client(
            sseBody = sse("event: error\ndata: {\"type\":\"error\",\"error\":{\"message\":\"overloaded\"}}")
        )

        val error = assertFailsWith<LlmStreamException> {
            client.streamChat(AvailableModels.ClaudeHaiku, listOf(ChatMessage.user("hi"))).toList()
        }
        assertEquals("stream_error", error.category)
    }

    @Test
    fun anthropic_refusal_stop_reason_is_reported() = runBlocking {
        val client = client(
            sseBody = sse(
                "event: message_delta\ndata: {\"delta\":{\"stop_reason\":\"refusal\"}}",
                "event: message_stop\ndata: {}"
            )
        )

        val error = assertFailsWith<LlmStreamException> {
            client.streamChat(AvailableModels.ClaudeHaiku, listOf(ChatMessage.user("hi"))).toList()
        }
        assertEquals("refusal", error.category)
    }

    @Test
    fun gemini_uses_roles_system_instruction_and_header_credential() = runBlocking {
        var request: HttpRequestData? = null
        val client = client(
            sseBody = sse("""data: {"candidates":[{"content":{"parts":[{"text":"Hey"}]},"finishReason":"STOP"}]}"""),
            captured = { request = it }
        )

        val tokens = client.streamChat(
            AvailableModels.GeminiFlash,
            listOf(
                ChatMessage.user("hello", id = "u1"),
                ChatMessage.assistant("hi", id = "a1")
            ),
            systemPrompt = "be nice"
        ).toList()

        assertEquals(listOf("Hey"), tokens)
        assertEquals("secret-key", request!!.headers["x-goog-api-key"])
        val url = request!!.url.toString()
        assertFalse(url.contains("secret-key"), "credential must not appear in the URL")
        assertFalse(url.contains("key="), "credential must not appear as a query parameter")
        assertTrue(url.contains("models/gemini-1.5-flash:streamGenerateContent"))
        val body = bodyOf(request!!)
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"role\":\"model\""))
        assertTrue(body.contains("systemInstruction"))
    }

    @Test
    fun gemini_safety_finish_reason_is_a_refusal() = runBlocking {
        val client = client(
            sseBody = sse("""data: {"candidates":[{"content":{"parts":[]},"finishReason":"SAFETY"}]}""")
        )

        val error = assertFailsWith<LlmStreamException> {
            client.streamChat(AvailableModels.GeminiFlash, listOf(ChatMessage.user("hi"))).toList()
        }
        assertEquals("refusal", error.category)
    }

    @Test
    fun http_error_carries_status_but_not_the_body_in_the_message() = runBlocking {
        val client = client(
            sseBody = """{"error":{"message":"Incorrect API key provided: sk-abc123"}}""",
            status = HttpStatusCode.Unauthorized
        )

        val error = assertFailsWith<LlmHttpException> {
            client.streamChat(AvailableModels.Gpt4oMini, listOf(ChatMessage.user("hi"))).toList()
        }
        assertEquals(401, error.status)
        assertFalse(error.message!!.contains("sk-abc123"), "the raw provider body must not be in the message")
        assertFalse(error.message!!.contains("Incorrect API key"))
    }

    @Test
    fun missing_key_fails_before_any_request() = runBlocking {
        val client = client(sseBody = "", apiKey = null)

        assertFailsWith<MissingApiKeyException> {
            client.streamChat(AvailableModels.Gpt4oMini, listOf(ChatMessage.user("hi"))).toList()
        }
        Unit
    }

    @Test
    fun malformed_event_payloads_are_skipped_not_fatal() = runBlocking {
        val client = client(
            sseBody = sse(
                "data: {not valid json",
                """data: {"choices":[{"delta":{"content":"ok"}}]}""",
                "data: [DONE]"
            )
        )

        val tokens = client.streamChat(AvailableModels.Gpt4oMini, listOf(ChatMessage.user("hi"))).toList()

        assertEquals(listOf("ok"), tokens)
    }

    private fun clientFor(errorBody: String) = KtorLlmStreamingClient(
        http = HttpClient(MockEngine {
            respond(
                content = ByteReadChannel(errorBody),
                status = HttpStatusCode.InternalServerError
            )
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
        keys = object : ApiKeyProvider {
            override suspend fun keyFor(provider: com.example.nexuschat.data.model.LlmProvider) = "secret-key"
        }
    )

    @Test
    fun server_error_preview_is_bounded() = runBlocking {
        val bigBody = "x".repeat(5000)
        val client = clientFor(bigBody)

        val error = assertFailsWith<LlmHttpException> {
            client.streamChat(AvailableModels.Gpt4oMini, listOf(ChatMessage.user("hi"))).toList()
        }
        assertTrue(error.bodyPreview.length <= 512, "error preview must be bounded")
    }
}
