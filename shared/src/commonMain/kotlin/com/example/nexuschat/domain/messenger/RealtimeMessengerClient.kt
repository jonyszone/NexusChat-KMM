package com.example.nexuschat.domain.messenger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

enum class RealtimeConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

data class CursorState(val conversationId: String, val sequence: Long)

sealed class RealtimeClientError(message: String) : IllegalStateException(message) {
    class Unauthorized : RealtimeClientError("Realtime identity was rejected")
    class UnsupportedVersion(val version: Int) : RealtimeClientError("Unsupported realtime protocol version: $version")
    class Remote(val code: String, detail: String?) : RealtimeClientError(detail ?: code)
    class Protocol(cause: Throwable) : RealtimeClientError("Invalid realtime envelope: ${cause.message}")
}

interface RealtimeMessengerClient {
    val connectionState: StateFlow<RealtimeConnectionState>
    val cursors: StateFlow<Map<String, Long>>
    suspend fun connect(devUserId: String)
    suspend fun connect(session: DeviceSession)
    suspend fun disconnect()
    suspend fun send(conversationId: String, senderId: String, idempotencyKey: String, body: String): WireMessage
    suspend fun history(conversationId: String, accountId: String, after: Long? = null): List<WireMessage>
    suspend fun acknowledge(conversationId: String, accountId: String, sequence: Long): Long
    suspend fun reconnect(conversationId: String, accountId: String, after: Long? = null): List<WireMessage>
}

class DefaultRealtimeMessengerClient(
    private val transport: RealtimeTransport,
    private val scope: CoroutineScope,
    private val requestId: () -> String = { Uuid.random().toString() },
) : RealtimeMessengerClient {
    private val state = MutableStateFlow(RealtimeConnectionState.DISCONNECTED)
    private val cursorState = MutableStateFlow<Map<String, Long>>(emptyMap())
    private var reader: Job? = null
    private var identity: String? = null

    override val connectionState: StateFlow<RealtimeConnectionState> = state.asStateFlow()
    override val cursors: StateFlow<Map<String, Long>> = cursorState.asStateFlow()

    override suspend fun connect(devUserId: String) {
        if (state.value == RealtimeConnectionState.CONNECTED) return
        state.value = if (identity == null) RealtimeConnectionState.CONNECTING else RealtimeConnectionState.RECONNECTING
        identity = devUserId
        transport.connect(devUserId)
        reader?.cancel()
        reader = scope.launch {
            transport.incoming.collect { raw ->
                // The request methods correlate their own responses by envelope id.
                received.send(raw)
            }
        }
        state.value = RealtimeConnectionState.CONNECTED
    }

    override suspend fun connect(session: DeviceSession) {
        if (state.value == RealtimeConnectionState.CONNECTED) return
        state.value = if (identity == null) RealtimeConnectionState.CONNECTING else RealtimeConnectionState.RECONNECTING
        identity = session.userId
        transport.connect(session)
        reader?.cancel()
        reader = scope.launch {
            transport.incoming.collect { raw -> received.send(raw) }
        }
        state.value = RealtimeConnectionState.CONNECTED
    }

    private val received = Channel<String>(Channel.UNLIMITED)
    private val requestMutex = Mutex()

    override suspend fun disconnect() {
        reader?.cancel()
        reader = null
        transport.disconnect()
        state.value = RealtimeConnectionState.DISCONNECTED
    }

    override suspend fun send(conversationId: String, senderId: String, idempotencyKey: String, body: String): WireMessage =
        request(ClientCommand.Send(conversationId, senderId, idempotencyKey, body)).let {
            (it as? ServerPayload.SendAck)?.message ?: error("Unexpected response")
        }

    override suspend fun history(conversationId: String, accountId: String, after: Long?): List<WireMessage> =
        historyLike(ClientCommand.History(conversationId, accountId, after ?: cursorState.value[conversationId] ?: 0), conversationId)

    override suspend fun reconnect(conversationId: String, accountId: String, after: Long?): List<WireMessage> =
        historyLike(ClientCommand.Reconnect(conversationId, accountId, after ?: cursorState.value[conversationId] ?: 0), conversationId)

    override suspend fun acknowledge(conversationId: String, accountId: String, sequence: Long): Long {
        val payload = request(ClientCommand.Ack(conversationId, accountId, sequence))
        val acknowledged = (payload as? ServerPayload.Ack)?.acknowledgedThrough ?: error("Unexpected response")
        advanceCursor(conversationId, acknowledged)
        return acknowledged
    }

    private suspend fun historyLike(command: ClientCommand, conversationId: String): List<WireMessage> {
        val payload = request(command) as? ServerPayload.History ?: error("Unexpected response")
        val max = payload.messages.maxOfOrNull { it.sequence } ?: payload.cursor
        if (max != null) advanceCursor(conversationId, max)
        return payload.messages
    }

    private suspend fun request(command: ClientCommand): ServerPayload = requestMutex.withLock {
        check(state.value == RealtimeConnectionState.CONNECTED) { "Realtime client is not connected" }
        val id = requestId()
        transport.send(encodeClientCommand(id, command))
        var result: ServerPayload? = null
        while (result == null) {
            val envelope = try {
                decodeServerEnvelope(received.receive())
            } catch (cause: Throwable) {
                throw RealtimeClientError.Protocol(cause)
            }
            if (envelope.version != REALTIME_PROTOCOL_VERSION) throw RealtimeClientError.UnsupportedVersion(envelope.version)
            if (envelope.id == id) result = envelope.payload.throwIfError()
        }
        result
    }

    private fun advanceCursor(conversationId: String, sequence: Long) {
        cursorState.value = cursorState.value + (conversationId to maxOf(sequence, cursorState.value[conversationId] ?: 0))
    }
}

private fun ServerPayload.throwIfError(): ServerPayload = when (this) {
    is ServerPayload.Error -> throw when (code) {
        "UNAUTHORIZED", "FORBIDDEN", "NOT_MEMBER" -> RealtimeClientError.Unauthorized()
        else -> RealtimeClientError.Remote(code, message)
    }
    else -> this
}

interface RealtimeMessengerRepository {
    suspend fun sendMessage(conversationId: String, senderId: String, idempotencyKey: String, body: String): WireMessage
    suspend fun catchUp(conversationId: String, accountId: String): List<WireMessage>
    suspend fun acknowledge(conversationId: String, accountId: String, sequence: Long): Long
}

class DefaultRealtimeMessengerRepository(private val client: RealtimeMessengerClient) : RealtimeMessengerRepository {
    override suspend fun sendMessage(conversationId: String, senderId: String, idempotencyKey: String, body: String) =
        client.send(conversationId, senderId, idempotencyKey, body)
    override suspend fun catchUp(conversationId: String, accountId: String) = client.reconnect(conversationId, accountId)
    override suspend fun acknowledge(conversationId: String, accountId: String, sequence: Long) =
        client.acknowledge(conversationId, accountId, sequence)
}
