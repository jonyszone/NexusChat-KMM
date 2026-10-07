package com.example.nexuschat.server

import com.example.nexuschat.server.auth.DevelopmentSessionAuthenticator
import com.example.nexuschat.server.auth.SessionAuthenticator
import com.example.nexuschat.server.domain.AccountRepository
import com.example.nexuschat.server.domain.DevelopmentAccountRepository
import com.example.nexuschat.server.domain.InMemoryMessageRepository
import com.example.nexuschat.server.domain.MessageRepository
import com.example.nexuschat.server.domain.SendCommand
import com.example.nexuschat.server.transport.ClientEnvelope
import com.example.nexuschat.server.transport.PROTOCOL_VERSION
import com.example.nexuschat.server.transport.ServerEnvelope
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.*
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

fun Application.module(messageRepository: MessageRepository? = null, authenticator: SessionAuthenticator = DevelopmentSessionAuthenticator(), accountRepository: AccountRepository = DevelopmentAccountRepository) {
    val messages = messageRepository ?: InMemoryMessageRepository(accountRepository.memberships())
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = false; classDiscriminator = "type" }) }
    install(WebSockets) { pingPeriodMillis = 20.seconds.inWholeMilliseconds; timeoutMillis = 30.seconds.inWholeMilliseconds }
    val sessions = ConcurrentHashMap<String, MutableSet<io.ktor.websocket.WebSocketSession>>()
    val json = Json { classDiscriminator = "type" }
    routing {
        get("/healthz") { call.respondText("ok") }
        route("/dev/v1") {
            get("/account") {
                val user = authenticator.authenticate(call)?.userId ?: return@get call.respond(io.ktor.http.HttpStatusCode.Unauthorized)
                val account = accountRepository.account(user) ?: return@get call.respond(io.ktor.http.HttpStatusCode.NotFound)
                call.respond(account)
            }
            get("/contacts") {
                val user = authenticator.authenticate(call)?.userId ?: return@get call.respond(io.ktor.http.HttpStatusCode.Unauthorized)
                call.respond(accountRepository.contacts(user))
            }
            get("/conversations/direct/{userId}") {
                val user = authenticator.authenticate(call)?.userId ?: return@get call.respond(io.ktor.http.HttpStatusCode.Unauthorized)
                val peer = call.parameters["userId"] ?: return@get call.respond(io.ktor.http.HttpStatusCode.BadRequest)
                val result = accountRepository.directConversation(user, peer)
                    ?: return@get call.respond(io.ktor.http.HttpStatusCode.NotFound)
                call.respond(result)
            }
        }
        webSocket("/v1/realtime") {
            val session = authenticator.authenticate(call) ?: return@webSocket
            val peers = sessions.computeIfAbsent(session.userId) { ConcurrentHashMap.newKeySet() }; peers += this
            try { for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val event = runCatching { json.decodeFromString<ClientEnvelope>(frame.readText()) }.getOrElse {
                    send(json.encodeToString<ServerEnvelope>(ServerEnvelope.Error("INVALID_ENVELOPE", "Unsupported envelope"))); continue
                }
                if (event.version != PROTOCOL_VERSION) { send(json.encodeToString<ServerEnvelope>(ServerEnvelope.Error("UNSUPPORTED_VERSION", "Unsupported protocol version"))); continue }
                when (event) {
                    is ClientEnvelope.SendMessage -> {
                        val response = runCatching { messages.accept(session.userId, SendCommand(event.conversationId,event.clientMessageId,event.idempotencyKey,event.text)) }
                            .fold({ it }, { ServerEnvelope.Error("MESSAGE_REJECTED", it.message ?: "Message rejected", event.clientMessageId, event.idempotencyKey) })
                        send(json.encodeToString<ServerEnvelope>(response))
                    }
                    is ClientEnvelope.History -> {
                        val response = runCatching { ServerEnvelope.History(event.conversationId,event.afterSequence,messages.history(session.userId,event.conversationId,event.afterSequence)) }
                            .getOrElse { ServerEnvelope.Error("HISTORY_REJECTED", it.message ?: "History rejected") }
                        send(json.encodeToString<ServerEnvelope>(response))
                    }
                    is ClientEnvelope.Presence -> broadcast(sessions,json,ServerEnvelope.Presence(session.userId,event.status))
                    is ClientEnvelope.Typing -> broadcast(sessions,json,ServerEnvelope.Typing(session.userId,event.conversationId,event.isTyping))
                    is ClientEnvelope.CallSignal -> broadcast(sessions,json,ServerEnvelope.CallSignal(session.userId,event.conversationId,event.callId,event.kind,event.payload))
                }
            } } finally { peers -= this; if (peers.isEmpty()) sessions.remove(session.userId,peers) }
        }
    }
}
private suspend fun broadcast(sessions: Map<String, Set<io.ktor.websocket.WebSocketSession>>, json: Json, event: ServerEnvelope) {
    sessions.values.flatten().forEach { runCatching { it.send(json.encodeToString<ServerEnvelope>(event)) } }
}
fun main() { io.ktor.server.netty.EngineMain.main(emptyArray()) }
