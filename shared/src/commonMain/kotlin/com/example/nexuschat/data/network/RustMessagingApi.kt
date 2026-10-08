package com.example.nexuschat.data.network

import com.example.nexuschat.domain.messenger.*
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlin.uuid.Uuid

@Serializable
private data class HttpEnvelope<T>(val version: Int, val id: String, val payload: T)

@Serializable
private data class AuthPayload(
    @SerialName("account_id") val accountId: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("expires_at") val expiresAt: Long,
)

@Serializable
private data class RustMessage(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val sequence: Long,
    @SerialName("idempotency_key") val idempotencyKey: String,
    val body: String,
) {
    fun wire() = WireMessage(id, conversationId, senderId, sequence, idempotencyKey, body)
}

@Serializable
private data class AckPayload(@SerialName("acknowledged_through") val sequence: Long)

class RustMessagingApi(
    private val http: HttpClient,
    private val allowInsecureTransport: Boolean = false,
) : AuthenticatedMessagingApi {
    private val json = Json { ignoreUnknownKeys = true }

    fun normalizeServer(value: String): String {
        val url = runCatching { Url(value.trim()) }.getOrElse { throw IllegalArgumentException("Invalid server address") }
        require(url.protocol == URLProtocol.HTTPS || (allowInsecureTransport && url.protocol == URLProtocol.HTTP)) { "Use an HTTPS server address" }
        require(url.host.isNotBlank() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() &&
            url.parameters.isEmpty() && url.fragment.isEmpty() && url.encodedPath in listOf("", "/")) { "Use the server's base address" }
        return url.toString().trimEnd('/')
    }

    override suspend fun authenticate(serverUrl: String, email: String, password: String, register: Boolean): MessengerSession {
        val server = normalizeServer(serverUrl)
        val body = buildJsonObject { put("email", email.trim()); put("password", password) }.toString()
        val payload = envelope(server, "/v1/auth/${if (register) "register" else "login"}", HttpMethod.Post, AuthPayload.serializer(), body = body)
        validateId(payload.accountId); validateId(payload.sessionId)
        if (payload.accessToken.isBlank() || payload.expiresAt <= 0) throw MessagingProtocolException()
        return MessengerSession(server, payload.accountId, payload.sessionId, payload.accessToken, payload.expiresAt)
    }

    override suspend fun verify(session: MessengerSession) {
        val account = envelope(session.serverUrl, "/v1/account/me", HttpMethod.Get, String.serializer(), session)
        if (account != session.accountId) throw MessagingProtocolException()
    }

    override suspend fun revoke(session: MessengerSession) {
        call(session.serverUrl, "/v1/auth/revoke", HttpMethod.Post, session,
            buildJsonObject { put("session_id", session.sessionId) }.toString())
    }

    override suspend fun conversations(session: MessengerSession): List<BackendConversation> =
        decode(ListSerializer(BackendConversation.serializer()), call(session.serverUrl, "/v1/messaging/conversations", HttpMethod.Get, session))
            .onEach { validateConversation(session, it) }

    override suspend fun createConversation(session: MessengerSession, peerId: String): BackendConversation {
        val peer = runCatching { Uuid.parse(peerId).toString() }.getOrElse { throw IllegalArgumentException("Invalid account ID") }
        require(peer != session.accountId) { "Choose another account" }
        val body = buildJsonObject { putJsonArray("members") { add(peer) } }.toString()
        return envelope(session.serverUrl, "/v1/messaging/conversations", HttpMethod.Post, BackendConversation.serializer(), session, body)
            .also {
                validateConversation(session, it)
                if (it.members != setOf(session.accountId, peer)) throw MessagingProtocolException()
            }
    }

    override suspend fun send(session: MessengerSession, conversationId: String, key: String, body: String): WireMessage {
        validateId(conversationId); validateId(key)
        val requestBody = buildJsonObject { put("idempotency_key", key); put("body", body) }.toString()
        return envelope(session.serverUrl, "/v1/messaging/conversations/$conversationId/messages", HttpMethod.Post, RustMessage.serializer(), session, requestBody)
            .wire().also {
                validateMessage(conversationId, it)
                if (it.senderId != session.accountId || it.idempotencyKey != key || it.body != body) throw MessagingProtocolException()
            }
    }

    override suspend fun history(session: MessengerSession, conversationId: String, after: Long): List<WireMessage> {
        validateId(conversationId); require(after >= 0)
        return decode(ListSerializer(RustMessage.serializer()), call(session.serverUrl, "/v1/messaging/conversations/$conversationId/messages?after=$after", HttpMethod.Get, session))
            .map { it.wire().also { message -> validateMessage(conversationId, message) } }
    }

    override suspend fun acknowledge(session: MessengerSession, conversationId: String, sequence: Long): Long {
        validateId(conversationId); require(sequence >= 0)
        return decode(AckPayload.serializer(), call(session.serverUrl, "/v1/messaging/conversations/$conversationId/ack", HttpMethod.Post, session,
            buildJsonObject { put("sequence", sequence) }.toString())).sequence.also { if (it < sequence) throw MessagingProtocolException() }
    }

    private fun validateConversation(session: MessengerSession, value: BackendConversation) {
        validateId(value.id)
        if (session.accountId !in value.members) throw MessagingProtocolException()
        value.members.forEach(::validateId)
    }

    private fun validateMessage(conversationId: String, value: WireMessage) {
        validateId(value.id); validateId(value.senderId); validateId(value.idempotencyKey)
        if (value.conversationId != conversationId || value.sequence <= 0) throw MessagingProtocolException()
    }

    private fun validateId(value: String) {
        if (runCatching { Uuid.parse(value) }.isFailure) throw MessagingProtocolException()
    }

    private suspend fun <T> envelope(server: String, path: String, method: HttpMethod, serializer: KSerializer<T>, session: MessengerSession? = null, body: String? = null): T {
        val value = decode(HttpEnvelope.serializer(serializer), call(server, path, method, session, body))
        if (value.version != 1) throw MessagingProtocolException()
        return value.payload
    }

    private fun <T> decode(serializer: KSerializer<T>, text: String): T = try {
        json.decodeFromString(serializer, text)
    } catch (cause: CancellationException) { throw cause
    } catch (_: Exception) { throw MessagingProtocolException() }

    private suspend fun call(server: String, path: String, method: HttpMethod, session: MessengerSession? = null, body: String? = null): String {
        val response = http.request(normalizeServer(server) + path) {
            this.method = method
            if (session != null) header("Authorization", "Bearer ${session.accessToken}")
            if (body != null) setBody(TextContent(body, ContentType.Application.Json))
        }
        if (response.status.value !in 200..299) throw MessagingHttpException(response.status.value)
        return response.bodyAsText()
    }
}
