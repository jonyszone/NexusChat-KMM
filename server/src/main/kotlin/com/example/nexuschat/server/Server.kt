package com.example.nexuschat.server

import com.example.nexuschat.server.auth.DevelopmentSessionAuthenticator
import com.example.nexuschat.server.auth.SessionAuthenticator
import com.example.nexuschat.server.domain.InMemoryMessageRepository
import com.example.nexuschat.server.domain.MessageRepository
import com.example.nexuschat.server.domain.SendCommand
import com.example.nexuschat.server.transport.ClientEnvelope
import com.example.nexuschat.server.transport.ServerEnvelope
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

fun Application.module(
    messageRepository: MessageRepository = InMemoryMessageRepository(),
    authenticator: SessionAuthenticator = DevelopmentSessionAuthenticator(),
) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = false; classDiscriminator = "type" }) }
    install(WebSockets) { pingPeriodMillis = 20.seconds.inWholeMilliseconds; timeoutMillis = 30.seconds.inWholeMilliseconds }
    val sessions = ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()
    val json = Json { classDiscriminator = "type" }

    routing {
        get("/healthz") { call.respondText("ok") }
        webSocket("/v1/realtime") {
            val session = authenticator.authenticate(call)
            if (session == null) { return@webSocket }
            val peers = sessions.computeIfAbsent(session.userId) { ConcurrentHashMap.newKeySet() }
            peers += this
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val event = runCatching { json.decodeFromString<ClientEnvelope>(frame.readText()) }.getOrElse { continue }
                    when (event) {
                        is ClientEnvelope.SendMessage -> {
                            val ack = messageRepository.accept(session.userId, SendCommand(event.conversationId, event.clientMessageId, event.idempotencyKey, event.text))
                            send(json.encodeToString<ServerEnvelope>(ack))
                        }
                        is ClientEnvelope.Presence -> broadcast(sessions, json, ServerEnvelope.Presence(session.userId, event.status))
                        is ClientEnvelope.Typing -> broadcast(sessions, json, ServerEnvelope.Typing(session.userId, event.conversationId, event.isTyping))
                        is ClientEnvelope.CallSignal -> broadcast(sessions, json, ServerEnvelope.CallSignal(session.userId, event.conversationId, event.callId, event.kind, event.payload))
                    }
                }
            } finally { peers -= this; if (peers.isEmpty()) sessions.remove(session.userId, peers) }
        }
    }
}

private suspend fun broadcast(
    sessions: Map<String, Set<DefaultWebSocketServerSession>>,
    json: Json,
    event: ServerEnvelope,
) { sessions.values.flatten().forEach { runCatching { it.send(json.encodeToString<ServerEnvelope>(event)) } } }

fun main() { io.ktor.server.netty.EngineMain.main(emptyArray()) }
