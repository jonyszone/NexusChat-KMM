package com.example.nexuschat.server.domain

import com.example.nexuschat.server.transport.ServerEnvelope
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

interface MessageRepository {
    suspend fun accept(userId: String, command: SendCommand): ServerEnvelope.MessageAck
    suspend fun history(userId: String, conversationId: String, afterSequence: Long): List<ServerEnvelope.MessageAck>
}
data class SendCommand(val conversationId: String, val clientMessageId: String, val idempotencyKey: String, val text: String)

@Serializable private data class Stored(val owner: String, val ack: ServerEnvelope.MessageAck)
@Serializable private data class Snapshot(val entries: List<Stored> = emptyList())

/** Atomic-file development/test persistence; production must use a managed database. */
class FileMessageRepository(private val file: File, private val members: Map<String, Set<String>>) : MessageRepository {
    private val lock = Any()
    private val json = Json { classDiscriminator = "type" }
    private val entries = mutableListOf<Stored>().apply { if (file.exists()) addAll(json.decodeFromString<Snapshot>(file.readText()).entries) }
    override suspend fun accept(userId: String, command: SendCommand): ServerEnvelope.MessageAck = synchronized(lock) {
        require(members[command.conversationId]?.contains(userId) == true) { "conversation membership required" }
        require(command.conversationId.isNotBlank() && command.clientMessageId.isNotBlank() && command.idempotencyKey.isNotBlank() && command.text.isNotBlank()) { "message fields required" }
        entries.firstOrNull { it.owner == userId && it.ack.idempotencyKey == command.idempotencyKey }?.let { return@synchronized it.ack.copy(duplicate = true) }
        val seq = (entries.filter { it.ack.conversationId == command.conversationId }.maxOfOrNull { it.ack.sequence } ?: 0L) + 1
        val ack = ServerEnvelope.MessageAck(command.clientMessageId, command.idempotencyKey, "msg-$seq", command.conversationId, seq, false)
        persist(entries + Stored(userId, ack)); entries += Stored(userId, ack); ack
    }
    override suspend fun history(userId: String, conversationId: String, afterSequence: Long): List<ServerEnvelope.MessageAck> = synchronized(lock) {
        require(members[conversationId]?.contains(userId) == true) { "conversation membership required" }
        entries.map { it.ack }.filter { it.conversationId == conversationId && it.sequence > afterSequence }.sortedBy { it.sequence }
    }
    private fun persist(values: List<Stored>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile ?: File("."), file.name + ".tmp")
        temp.writeText(json.encodeToString(Snapshot(values)))
        check(temp.renameTo(file)) { "atomic message-store replacement failed" }
    }
}

/** Volatile scripted-user repository for development/tests only. */
class InMemoryMessageRepository(private val members: Map<String, Set<String>> = mapOf("c" to setOf("alice", "bob"))) : MessageRepository {
    private val lock = Any(); private val entries = mutableListOf<Stored>()
    override suspend fun accept(userId: String, command: SendCommand): ServerEnvelope.MessageAck = synchronized(lock) {
        require(members[command.conversationId]?.contains(userId) == true) { "conversation membership required" }
        require(command.conversationId.isNotBlank() && command.clientMessageId.isNotBlank() && command.idempotencyKey.isNotBlank() && command.text.isNotBlank()) { "message fields required" }
        entries.firstOrNull { it.owner == userId && it.ack.idempotencyKey == command.idempotencyKey }?.let { return@synchronized it.ack.copy(duplicate = true) }
        val seq = (entries.filter { it.ack.conversationId == command.conversationId }.maxOfOrNull { it.ack.sequence } ?: 0L) + 1
        ServerEnvelope.MessageAck(command.clientMessageId, command.idempotencyKey, "msg-$seq", command.conversationId, seq, false).also { entries += Stored(userId, it) }
    }
    override suspend fun history(userId: String, conversationId: String, afterSequence: Long): List<ServerEnvelope.MessageAck> = synchronized(lock) {
        require(members[conversationId]?.contains(userId) == true) { "conversation membership required" }
        entries.map { it.ack }.filter { it.conversationId == conversationId && it.sequence > afterSequence }.sortedBy { it.sequence }
    }
}
