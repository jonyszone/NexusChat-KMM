package com.example.nexuschat.data.network

import com.example.nexuschat.domain.messenger.RealtimeTransport
import com.example.nexuschat.domain.messenger.DeviceSession
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/** Rust realtime adapter. The development overload is retained for local fixtures only. */
class KtorRustRealtimeTransport(
    private val http: HttpClient,
    private val endpoint: String,
) : RealtimeTransport {
    private var session: DefaultClientWebSocketSession? = null

    override val incoming: Flow<String>
        get() = requireSession().incoming.receiveAsFlow()
            .filterIsInstance<Frame.Text>()
            .map { it.readText() }

    override suspend fun connect(devUserId: String) {
        session = http.webSocketSession(HttpMethod.Get, endpoint) {
            header("X-Dev-User-Id", devUserId)
        }
    }

    override suspend fun connect(session: DeviceSession) {
        this.session = http.webSocketSession(HttpMethod.Get, endpoint) {
            header("Authorization", "Bearer ${session.accessToken}")
            header("X-Device-Id", session.deviceId)
        }
    }

    override suspend fun send(text: String) {
        requireSession().send(Frame.Text(text))
    }

    override suspend fun disconnect() {
        session?.close()
        session = null
    }

    private fun requireSession(): DefaultClientWebSocketSession =
        checkNotNull(session) { "Rust realtime WebSocket is not connected" }
}
