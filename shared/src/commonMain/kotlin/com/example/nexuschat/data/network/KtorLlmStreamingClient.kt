package com.example.nexuschat.data.network

import com.example.nexuschat.data.model.AiModel
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.data.model.ChatRole
import com.example.nexuschat.data.model.LlmProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * BYOK key source. Implemented per-platform with encrypted storage:
 * Android = EncryptedSharedPreferences, iOS = Keychain, Desktop = OS keychain.
 * The streaming client never persists keys itself.
 */
interface ApiKeyProvider {
    suspend fun keyFor(provider: LlmProvider): String?
}

/** Read/write BYOK credentials. Implemented with platform secure storage. */
interface ApiKeyStore : ApiKeyProvider {
    suspend fun setKey(provider: LlmProvider, value: String)
    suspend fun clearKey(provider: LlmProvider)
}

/** Thrown when no key is stored for the selected provider. */
class MissingApiKeyException(val provider: LlmProvider) :
    IllegalStateException("No API key stored for $provider. Add it in Settings (BYOK).")

/**
 * Thrown when a stored credential exists but cannot be decrypted — corrupt
 * ciphertext or a Keystore key that was invalidated (for example after a
 * device restore). Kept distinct from [MissingApiKeyException] so the user is
 * told to re-enter the key rather than silently dropped into demo mode.
 */
class CredentialCorruptedException(val provider: LlmProvider, cause: Throwable? = null) :
    IllegalStateException("Stored credential for $provider could not be decrypted.", cause)

/**
 * Non-2xx HTTP from the LLM backend. The [message] deliberately contains only
 * the status code; the (bounded, sanitized) body preview is exposed separately
 * so a provider body can never leak into a snackbar or crash report by accident.
 */
class LlmHttpException(val status: Int, val bodyPreview: String) :
    IllegalStateException("LLM HTTP $status")

/**
 * A 2xx stream that still failed: a provider error event, an unexpected EOF
 * before the completion marker, a blocked/refused response, or malformed SSE.
 * [category] is one of `stream_error`, `unexpected_eof`, `refusal`, `malformed`.
 */
class LlmStreamException(val category: String, message: String) :
    IllegalStateException(message)

// ---------------------------------------------------------------------------
// Wire DTOs (only fields we need — Json { ignoreUnknownKeys = true })
// ---------------------------------------------------------------------------

@Serializable
private data class OpenAiMessage(val role: String, val content: String)

@Serializable
private data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    // No default: `stream` must always appear on the wire regardless of the
    // Json encoder configuration (OpenAI defaults to non-streaming).
    val stream: Boolean,
    @SerialName("max_tokens") val maxTokens: Int? = null
)

@Serializable
private data class OpenAiDelta(val content: String? = null)

@Serializable
private data class OpenAiChoice(
    val delta: OpenAiDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
private data class OpenAiError(val message: String? = null, val type: String? = null)

@Serializable
private data class OpenAiChunk(
    val choices: List<OpenAiChoice> = emptyList(),
    val error: OpenAiError? = null
)

@Serializable
private data class AnthropicMessage(val role: String, val content: String)

@Serializable
private data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int = 1024,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    // No default: must always serialize so the response is streamed.
    val stream: Boolean
)

@Serializable
private data class AnthropicDelta(val text: String? = null)

@Serializable
private data class AnthropicChunk(val delta: AnthropicDelta? = null)

@Serializable
private data class AnthropicMessageDelta(val stop_reason: String? = null)

@Serializable
private data class AnthropicMessageDeltaEvent(val delta: AnthropicMessageDelta? = null)

@Serializable
private data class GeminiPart(val text: String? = null)

@Serializable
private data class GeminiContent(val role: String? = null, val parts: List<GeminiPart>)

@Serializable
private data class GeminiRequest(
    val contents: List<GeminiContent>,
    @SerialName("systemInstruction") val systemInstruction: GeminiContent? = null
)

@Serializable
private data class GeminiCandidate(
    val content: GeminiContent? = null,
    @SerialName("finishReason") val finishReason: String? = null
)

@Serializable
private data class GeminiError(val message: String? = null, val status: String? = null)

@Serializable
private data class GeminiChunk(
    val candidates: List<GeminiCandidate> = emptyList(),
    val error: GeminiError? = null
)

/**
 * Unified streaming engine.
 *
 * Why manual SSE parsing instead of Ktor's `SSE` plugin?
 * - Ktor's SSE plugin only supports GET. All chat-completion endpoints
 *   require POST with a JSON body.
 * - So we POST, then read the `text/event-stream` channel and run it through
 *   [SseFramer], which handles event boundaries, multiline data, comments,
 *   CRLF and fragmented UTF-8 correctly, then emit provider-normalised deltas.
 *
 * Usage: inject HttpClient with platform engine + Json, inject keys.
 */
class KtorLlmStreamingClient(
    private val http: HttpClient,
    private val keys: ApiKeyProvider,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {

    /**
     * Single entry point for UI / repository.
     * Converts raw SSE events into a unified Flow<String> token stream.
     */
    fun streamChat(
        model: AiModel,
        history: List<ChatMessage>,
        systemPrompt: String? = null
    ): Flow<String> = flow {
        val apiKey = keys.keyFor(model.provider) ?: throw MissingApiKeyException(model.provider)
        val visible = history.filter { it.role != ChatRole.SYSTEM }

        when (model.provider) {
            LlmProvider.OPENAI -> emitAll(
                streamOpenAiCompatible(
                    baseUrl = "https://api.openai.com/v1/chat/completions",
                    modelId = model.id,
                    messages = mergeSystem(visible, systemPrompt),
                    authHeader = HttpHeaders.Authorization to "Bearer $apiKey"
                )
            )

            LlmProvider.DEEPSEEK -> emitAll(
                streamOpenAiCompatible(
                    baseUrl = "https://api.deepseek.com/chat/completions",
                    modelId = model.id,
                    messages = mergeSystem(visible, systemPrompt),
                    authHeader = HttpHeaders.Authorization to "Bearer $apiKey"
                )
            )

            LlmProvider.ANTHROPIC -> emitAll(streamAnthropic(model, visible, systemPrompt, apiKey))

            LlmProvider.GEMINI -> emitAll(streamGemini(model, visible, systemPrompt, apiKey))
        }
    }.flowOn(Dispatchers.Default)

    // -- OpenAI-compatible (OpenAI + DeepSeek) -------------------------------

    private fun streamOpenAiCompatible(
        baseUrl: String,
        modelId: String,
        messages: List<ChatMessage>,
        authHeader: Pair<String, String>
    ): Flow<String> = flow {
        val body = OpenAiRequest(
            model = modelId,
            messages = messages.map { OpenAiMessage(role = it.role.wireName(), content = it.content) },
            stream = true
        )
        http.preparePost(baseUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            header(authHeader.first, authHeader.second)
            setBody(body)
        }.execute { response ->
            checkSuccess(response)
            val framer = SseFramer()
            var terminated = false
            collectEvents(response, framer) { event ->
                val data = event.data
                if (data.isBlank()) return@collectEvents true
                if (data == OPENAI_DONE) {
                    terminated = true
                    return@collectEvents false
                }
                val chunk = decodeOrNull(OpenAiChunk.serializer(), data)
                if (chunk?.error != null) {
                    throw LlmStreamException("stream_error", "The provider reported a streaming error.")
                }
                chunk?.choices?.firstOrNull()?.let { choice ->
                    if (choice.finishReason == "content_filter") {
                        throw LlmStreamException("refusal", "The provider blocked this response.")
                    }
                    choice.delta?.content?.takeIf { it.isNotEmpty() }?.let { emit(it) }
                }
                true
            }
            if (!terminated) {
                throw LlmStreamException("unexpected_eof", "The provider stream ended before completion.")
            }
        }
    }

    // -- Anthropic -----------------------------------------------------------

    private fun streamAnthropic(
        model: AiModel,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        apiKey: String
    ): Flow<String> = flow {
        val body = AnthropicRequest(
            model = model.id,
            messages = messages.map {
                AnthropicMessage(
                    role = if (it.role == ChatRole.USER) "user" else "assistant",
                    content = it.content
                )
            },
            // Anthropic takes the system prompt as a top-level field, not a message.
            system = systemPrompt?.takeIf { it.isNotBlank() },
            stream = true
        )
        http.preparePost("https://api.anthropic.com/v1/messages") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(body)
        }.execute { response ->
            checkSuccess(response)
            val framer = SseFramer()
            var terminated = false
            collectEvents(response, framer) { event ->
                when (event.event) {
                    "content_block_delta" -> {
                        val token = decodeOrNull(AnthropicChunk.serializer(), event.data)
                            ?.delta?.text?.takeIf { it.isNotEmpty() }
                        if (token != null) emit(token)
                    }
                    "message_delta" -> {
                        val stop = decodeOrNull(AnthropicMessageDeltaEvent.serializer(), event.data)
                            ?.delta?.stop_reason
                        if (stop == "refusal") {
                            throw LlmStreamException("refusal", "The provider blocked this response.")
                        }
                    }
                    "message_stop" -> {
                        terminated = true
                        return@collectEvents false
                    }
                    "error" -> throw LlmStreamException("stream_error", "The provider reported a streaming error.")
                }
                true
            }
            if (!terminated) {
                throw LlmStreamException("unexpected_eof", "The provider stream ended before completion.")
            }
        }
    }

    // -- Gemini --------------------------------------------------------------

    private fun streamGemini(
        model: AiModel,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        apiKey: String
    ): Flow<String> = flow {
        val contents = messages.map { message ->
            // Gemini uses user/model roles rather than user/assistant.
            GeminiContent(
                role = if (message.role == ChatRole.ASSISTANT) "model" else "user",
                parts = listOf(GeminiPart(message.content))
            )
        }
        val body = GeminiRequest(
            contents = contents,
            systemInstruction = systemPrompt
                ?.takeIf { it.isNotBlank() }
                ?.let { GeminiContent(parts = listOf(GeminiPart(it))) }
        )
        // Credential travels in a header, never the query string, so it cannot
        // end up in URLs, proxy logs or browser history.
        http.preparePost(
            "https://generativelanguage.googleapis.com/v1beta/models/${model.id}:streamGenerateContent?alt=sse"
        ) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            header("x-goog-api-key", apiKey)
            setBody(body)
        }.execute { response ->
            checkSuccess(response)
            val framer = SseFramer()
            var terminated = false
            collectEvents(response, framer) { event ->
                if (event.data.isBlank()) return@collectEvents true
                val chunk = decodeOrNull(GeminiChunk.serializer(), event.data)
                if (chunk?.error != null) {
                    throw LlmStreamException("stream_error", "The provider reported a streaming error.")
                }
                val candidate = chunk?.candidates?.firstOrNull()
                val finish = candidate?.finishReason
                if (finish in GEMINI_BLOCK_REASONS) {
                    throw LlmStreamException("refusal", "The provider blocked this response.")
                }
                // Gemini can send the final text and the finishReason together,
                // so emit the parts before terminating on the finish reason.
                candidate?.content?.parts?.forEach { part ->
                    part.text?.takeIf { it.isNotEmpty() }?.let { emit(it) }
                }
                if (finish != null) {
                    terminated = true
                    return@collectEvents false
                }
                true
            }
            if (!terminated) {
                throw LlmStreamException("unexpected_eof", "The provider stream ended before completion.")
            }
        }
    }

    // -- SSE helpers ---------------------------------------------------------

    private suspend fun collectEvents(
        response: HttpResponse,
        framer: SseFramer,
        onEvent: suspend (SseEvent) -> Boolean
    ) {
        val channel = response.bodyAsChannel()
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            val event = framer.feed(line) ?: continue
            if (!onEvent(event)) return
        }
        framer.finish()?.let { onEvent(it) }
    }

    private suspend fun checkSuccess(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            throw LlmHttpException(response.status.value, readBoundedPreview(response))
        }
    }

    /** Reads at most [ERROR_PREVIEW_LIMIT] characters, then strips control characters. */
    private suspend fun readBoundedPreview(response: HttpResponse): String {
        val channel = response.bodyAsChannel()
        val builder = StringBuilder()
        while (builder.length < ERROR_PREVIEW_LIMIT) {
            val line = runCatching { channel.readUTF8Line() }.getOrNull() ?: break
            builder.append(line)
        }
        return builder.toString()
            .filter { it.code in 32..126 || it == '\n' }
            .take(ERROR_PREVIEW_LIMIT)
    }

    private fun <T> decodeOrNull(serializer: KSerializer<T>, data: String): T? =
        runCatching { json.decodeFromString(serializer, data) }.getOrNull()

    private suspend fun kotlinx.coroutines.flow.FlowCollector<String>.emitAll(
        upstream: Flow<String>
    ) {
        upstream.collect { emit(it) }
    }

    private fun mergeSystem(
        visible: List<ChatMessage>,
        systemPrompt: String?
    ): List<ChatMessage> =
        if (systemPrompt.isNullOrBlank()) visible
        else listOf(ChatMessage.system(systemPrompt)) + visible

    private fun ChatRole.wireName(): String = when (this) {
        ChatRole.SYSTEM -> "system"
        ChatRole.USER -> "user"
        ChatRole.ASSISTANT -> "assistant"
    }

    private companion object {
        const val OPENAI_DONE = "[DONE]"
        const val ERROR_PREVIEW_LIMIT = 512
        val GEMINI_BLOCK_REASONS = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "RECITATION")
    }
}

// ---------------------------------------------------------------------------
// Pure, unit-testable SSE framing (no Ktor / no coroutines)
// ---------------------------------------------------------------------------

/** One dispatched Server-Sent Event. */
data class SseEvent(val event: String?, val data: String)

/**
 * Line-oriented SSE framer implementing the event-stream rules we rely on:
 * events are separated by a blank line, `data:` accumulates (joined with `\n`),
 * `event:` names the type, `:` starts a comment, and a trailing CR is ignored.
 * A final event without a trailing blank line is flushed by [finish].
 */
class SseFramer {
    private val data = StringBuilder()
    private var event: String? = null
    private var hasData = false

    /** Feed one line; returns a complete event when a blank separator is seen. */
    fun feed(rawLine: String): SseEvent? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) {
            val dispatched = dispatch()
            event = null
            return dispatched
        }
        if (line.startsWith(":")) return null // comment
        val colon = line.indexOf(':')
        val field: String
        val value: String
        if (colon < 0) {
            field = line
            value = ""
        } else {
            field = line.substring(0, colon)
            value = line.substring(colon + 1).removePrefix(" ")
        }
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            // "id:" / "retry:" / unknown fields are intentionally ignored.
        }
        return null
    }

    /** Flush any pending event at end of stream. */
    fun finish(): SseEvent? {
        val dispatched = dispatch()
        event = null
        return dispatched
    }

    private fun dispatch(): SseEvent? {
        if (!hasData && event == null) return null
        val payload = data.toString()
        data.setLength(0)
        hasData = false
        return SseEvent(event, payload)
    }
}

// ---------------------------------------------------------------------------
// Pure, unit-testable delta extractors (no Ktor / no coroutines)
// ---------------------------------------------------------------------------

internal fun parseOpenAiDelta(dataJson: String, json: Json = defaultJson()): String? {
    if (dataJson.isBlank() || dataJson == "[DONE]") return null
    val chunk = json.decodeFromString(OpenAiChunk.serializer(), dataJson)
    return chunk.choices.firstOrNull()?.delta?.content?.takeIf { it.isNotEmpty() }
}

internal fun parseAnthropicDelta(
    event: String?,
    dataJson: String,
    json: Json = defaultJson()
): String? {
    if (event != null && event != "content_block_delta") return null
    if (dataJson.isBlank()) return null
    val chunk = json.decodeFromString(AnthropicChunk.serializer(), dataJson)
    return chunk.delta?.text?.takeIf { it.isNotEmpty() }
}

internal fun parseGeminiDelta(dataJson: String, json: Json = defaultJson()): String? {
    if (dataJson.isBlank()) return null
    val chunk = json.decodeFromString(GeminiChunk.serializer(), dataJson)
    return chunk.candidates.firstOrNull()
        ?.content?.parts?.firstOrNull()?.text?.takeIf { it.isNotEmpty() }
}

private fun defaultJson(): Json = Json { ignoreUnknownKeys = true; isLenient = true }
