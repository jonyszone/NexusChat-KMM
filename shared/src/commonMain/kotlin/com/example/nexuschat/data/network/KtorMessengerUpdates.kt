package com.example.nexuschat.data.network

import com.example.nexuschat.domain.messenger.MessengerSession
import com.example.nexuschat.domain.messenger.MessengerUpdates
import com.example.nexuschat.domain.messenger.MessagingProtocolException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.appendPathSegments
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

@Serializable
private data class UpdateEnvelope(val version: Int, val id: String, val payload: UpdatePayload)

@Serializable
private data class UpdatePayload(val type: String, @SerialName("conversation_id") val conversationId: String? = null)

internal fun validateMessagingUpdate(raw: String) {
    try {
        val envelope = Json.decodeFromString<UpdateEnvelope>(raw)
        require(envelope.version == 1)
        Uuid.parse(envelope.id)
        when (envelope.payload.type) {
            "READY" -> require(envelope.payload.conversationId == null)
            "CHANGED" -> Uuid.parse(checkNotNull(envelope.payload.conversationId))
            else -> throw MessagingProtocolException()
        }
    } catch (_: Exception) {
        throw MessagingProtocolException()
    }
}

class KtorMessengerUpdates(
    private val http: HttpClient,
    private val allowInsecureTransport: Boolean = false,
) : MessengerUpdates {
    override fun events(session: MessengerSession): Flow<Unit> = flow {
        val server = RustMessagingApi(http, allowInsecureTransport).normalizeServer(session.serverUrl)
        val endpoint = URLBuilder(server).apply {
            protocol = if (protocol == URLProtocol.HTTPS) URLProtocol.WSS else URLProtocol.WS
            appendPathSegments("v1", "messaging", "events")
        }.buildString()
        http.webSocket(urlString = endpoint, request = {
            header("Authorization", "Bearer ${session.accessToken}")
        }) {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    validateMessagingUpdate(frame.readText())
                    emit(Unit)
                }
            }
        }
    }
}
