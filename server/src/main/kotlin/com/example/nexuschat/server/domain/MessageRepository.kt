package com.example.nexuschat.server.domain

import com.example.nexuschat.server.transport.ServerEnvelope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface MessageRepository {
    suspend fun accept(userId: String, command: SendCommand): ServerEnvelope.MessageAck
}

data class SendCommand(val conversationId: String, val clientMessageId: String, val idempotencyKey: String, val text: String)

class InMemoryMessageRepository : MessageRepository {
    private val sequence = AtomicLong(0)
    private val accepted = ConcurrentHashMap<Pair<String, String>, ServerEnvelope.MessageAck>()

    override suspend fun accept(userId: String, command: SendCommand): ServerEnvelope.MessageAck {
        require(command.conversationId.isNotBlank() && command.clientMessageId.isNotBlank()) { "message identifiers are required" }
        require(command.idempotencyKey.isNotBlank()) { "idempotencyKey is required" }
        require(command.text.isNotBlank()) { "message text is required" }
        val key = userId to command.idempotencyKey
        val candidate = ServerEnvelope.MessageAck(command.clientMessageId, command.idempotencyKey, "msg-${sequence.incrementAndGet()}", command.conversationId, sequence.get(), false)
        val existing = accepted.putIfAbsent(key, candidate)
        return existing?.copy(duplicate = true) ?: candidate
    }
}
