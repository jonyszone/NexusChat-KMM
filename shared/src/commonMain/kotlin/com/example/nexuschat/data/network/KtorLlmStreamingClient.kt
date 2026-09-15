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

/** Thrown when no key is stored for the selected provider. */
class MissingApiKeyException(val provider: LlmProvider) :
    IllegalStateException("No API key stored for $provider. Add it in Settings (BYOK).")

/** Thrown for non-2xx HTTP from the LLM backend. */
class LlmHttpException(val status: Int, val bodyPreview: String) :
    IllegalStateException("LLM HTTP $status: $bodyPreview")

// ---------------------------------------------------------------------------
// Wire DTOs (only fields we need — Json { ignoreUnknownKeys = true })
// ---------------------------------------------------------------------------

@Serializable
private data class OpenAiMessage(val role: String, val content: String)

@Serializable
private data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val stream: Boolean = true,
    @SerialName("max_tokens") val maxTokens: Int? = null
)

@Serializable
private data class OpenAiDelta(val content: String? = null)

@Serializable
private data class OpenAiChoice(val delta: OpenAiDelta? = null)

@Serializable
private data class OpenAiChunk(val choices: List<OpenAiChoice> = emptyList())

@Serializable
private data class AnthropicContent(val type: String = "text", val text: String)

@Serializable
private data class AnthropicMessage(val role: String, val content: String)

@Serializable
private data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int = 1024,
    val messages: List<AnthropicMessage>,
    val stream: Boolean = true
)

@Serializable
private data class AnthropicDelta(val text: String? = null)

@Serializable
private data class AnthropicChunk(val delta: AnthropicDelta? = null)

@Serializable
private data class GeminiPart(val text: String? = null)

@Serializable
private data class GeminiContent(val parts: List<GeminiPart>)

@Serializable
private data class GeminiRequest(val contents: List<GeminiContent>)

@Serializable
private data class GeminiCandidate(val content: GeminiContent? = null)

@Serializable
private data class GeminiChunk(val candidates: List<GeminiCandidate> = emptyList())

/**
 * Unified streaming engine.
 *
 * Why manual SSE parsing instead of Ktor's `SSE` plugin?
 * - Ktor's SSE plugin only supports GET. All chat-completion endpoints
 *   require POST with a JSON body.
 * - So we POST with ContentNegotiation(JSON), then read the
 *   `text/event-stream` ByteReadChannel line-by-line and emit
 *   provider-normalised text deltas as Flow<String>.
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
            LlmProvider.OPENAI -> emitAll(streamOpenAiCompatible(
                baseUrl = "https://api.openai.com/v1/chat/completions",
                modelId = model.id,
                messages = mergeSystem(visible, systemPrompt),
                authHeader = HttpHeaders.Authorization to "Bearer $apiKey",
                extraHeaders = emptyMap()
            ) { raw -> parseOpenAiDelta(raw, json) })

            LlmProvider.DEEPSEEK -> emitAll(streamOpenAiCompatible(
                baseUrl = "https://api.deepseek.com/chat/completions",
                modelId = model.id,
                messages = mergeSystem(visible, systemPrompt),
                authHeader = HttpHeaders.Authorization to "Bearer $apiKey",
                extraHeaders = emptyMap()
            ) { raw -> parseOpenAiDelta(raw, json) })

            LlmProvider.ANTHROPIC -> emitAll(streamAnthropic(model, visible, apiKey))

            LlmProvider.GEMINI -> emitAll(streamGemini(model, visible, systemPrompt, apiKey))
        }
    }.flowOn(Dispatchers.Default)

    // -- OpenAI-compatible (OpenAI + DeepSeek) -------------------------------

    private fun streamOpenAiCompatible(
        baseUrl: String,
        modelId: String,
        messages: List<ChatMessage>,
        authHeader: Pair<String, String>,
        extraHeaders: Map<String, String>,
        extract: (String) -> String?
    ): Flow<String> = flow {
        val body = OpenAiRequest(
            model = modelId,
            messages = messages.map {
                OpenAiMessage(role = it.role.wireName(), content = it.content)
            }
        )
        http.preparePost(baseUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            header(authHeader.first, authHeader.second)
            extraHeaders.forEach { (k, v) -> header(k, v) }
            setBody(body)
        }.execute { response ->
            checkSuccess(response)
            response.eventDataLines().collect { data ->
                if (data == "[DONE]") return@collect
                val token = runCatching { extract(data) }.getOrNull()
                if (!token.isNullOrEmpty()) emit(token)
            }
        }
    }

    private fun streamAnthropic(
        model: AiModel,
        messages: List<ChatMessage>,
        apiKey: String
    ): Flow<String> = flow {
        val body = AnthropicRequest(
            model = model.id,
            messages = messages
                .filter { it.role != ChatRole.SYSTEM }
                .map {
                    AnthropicMessage(
                        role = when (it.role) {
                            ChatRole.USER -> "user"
                            else -> "assistant"
                        },
                        content = it.content
                    )
                }
        )
        http.preparePost("https://api.anthropic.com/v1/messages") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(body)
        }.execute { response ->
            checkSuccess(response)
            // Anthropic sends `event: <type>` + `data: <json>` pairs.
            // We only care about content_block_delta with delta.text.
            var pendingEvent: String? = null
            response.sseRawLines().collect { line ->
                when {
                    line.startsWith("event:") -> pendingEvent = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val data = line.removePrefix("data:").trim()
                        val token = runCatching {
                            parseAnthropicDelta(pendingEvent, data, json)
                        }.getOrNull()
                        if (!token.isNullOrEmpty()) emit(token)
                        pendingEvent = null
                    }
                    line.isBlank() -> pendingEvent = null
                }
            }
        }
    }

    private fun streamGemini(
        model: AiModel,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        apiKey: String
    ): Flow<String> = flow {
        val contents = buildList {
            if (systemPrompt != null) {
                add(GeminiContent(listOf(GeminiPart(systemPrompt))))
            }
            messages.forEach { add(GeminiContent(listOf(GeminiPart(it.content)))) }
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${model.id}:streamGenerateContent?alt=sse&key=$apiKey"
        http.preparePost(url) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            setBody(GeminiRequest(contents))
        }.execute { response ->
            checkSuccess(response)
            response.eventDataLines().collect { data ->
                val token = runCatching { parseGeminiDelta(data, json) }.getOrNull()
                if (!token.isNullOrEmpty()) emit(token)
            }
        }
    }

    // -- SSE helpers ---------------------------------------------------------

    private suspend fun checkSuccess(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            // Read a small preview without consuming the stream twice.
            val preview = runCatching {
                response.bodyAsChannel().readUTF8Line() ?: ""
            }.getOrDefault("")
            throw LlmHttpException(response.status.value, preview.take(500))
        }
    }

    /** Emits only the payload of `data: ...` lines. */
    private fun HttpResponse.eventDataLines(): Flow<String> = flow {
        bodyAsChannel().let { ch ->
            while (!ch.isClosedForRead) {
                val line = ch.readUTF8Line() ?: break
                if (line.startsWith("data:")) {
                    val data = line.removePrefix("data:").trim()
                    if (data.isNotEmpty()) emit(data)
                }
            }
        }
    }

    /** Emits raw `event:` / `data:` / blank lines for stateful parsers. */
    private fun HttpResponse.sseRawLines(): Flow<String> = flow {
        val ch = bodyAsChannel()
        while (!ch.isClosedForRead) {
            emit(ch.readUTF8Line() ?: break)
        }
    }

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
