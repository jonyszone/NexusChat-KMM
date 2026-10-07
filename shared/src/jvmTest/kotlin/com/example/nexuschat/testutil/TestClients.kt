package com.example.nexuschat.testutil

import com.example.nexuschat.data.model.AiModel
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.data.model.ChatMode
import com.example.nexuschat.data.model.LlmProvider
import com.example.nexuschat.data.network.ApiKeyProvider
import com.example.nexuschat.data.network.KtorLlmStreamingClient
import com.example.nexuschat.domain.repository.ChatRepository
import com.example.nexuschat.domain.repository.ChatSession
import com.example.nexuschat.domain.repository.ChatStorage
import com.example.nexuschat.domain.repository.InMemoryChatStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/** A Ktor client backed by MockEngine that returns a fixed SSE body. */
fun mockStreamingClient(
    apiKey: String? = "test-key",
    sseBody: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    onRequest: ((HttpRequestData) -> Unit)? = null
): KtorLlmStreamingClient {
    val engine = MockEngine { request ->
        onRequest?.invoke(request)
        respond(
            content = ByteReadChannel(sseBody),
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
        )
    }
    val http = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
    }
    return KtorLlmStreamingClient(
        http = http,
        keys = object : ApiKeyProvider {
            override suspend fun keyFor(provider: LlmProvider): String? = apiKey
        }
    )
}

fun sse(vararg events: String): String = events.joinToString("") { "$it\n\n" }

/** Repository test double backed by in-memory storage with a scriptable stream. */
class FakeChatRepository(
    override val mode: ChatMode = ChatMode.BYOK,
    val storage: ChatStorage = InMemoryChatStorage(),
    var streamFactory: (AiModel, List<ChatMessage>, String?) -> Flow<String> = { _, _, _ -> kotlinx.coroutines.flow.flowOf("ok") }
) : ChatRepository {

    override fun streamReply(model: AiModel, history: List<ChatMessage>, systemPrompt: String?) =
        streamFactory(model, history, systemPrompt)

    override fun observeSessions() = storage.observeSessions()
    override fun observeMessages(sessionId: String) = storage.observeMessages(sessionId)
    override suspend fun createSession(session: ChatSession) = storage.upsertSession(session)
    override suspend fun renameSession(sessionId: String, title: String) = storage.renameSession(sessionId, title)
    override suspend fun deleteSession(sessionId: String) = storage.deleteSession(sessionId)
    override suspend fun clearMessages(sessionId: String) = storage.clearMessages(sessionId)
    override suspend fun beginTurn(
        sessionId: String,
        user: ChatMessage,
        assistantPlaceholder: ChatMessage,
        model: AiModel,
        systemPrompt: String?,
        updatedAt: Long
    ) = storage.beginTurn(sessionId, user, assistantPlaceholder, model.id, systemPrompt, updatedAt)

    override suspend fun upsertMessage(sessionId: String, message: ChatMessage) =
        storage.upsertMessage(sessionId, message)

    override suspend fun recoverInterrupted(sessionId: String) = storage.markInterrupted(sessionId)
}
