package com.example.nexuschat.domain.messenger

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

const val REALTIME_PROTOCOL_VERSION = 1

@Serializable
sealed class ClientCommand {
    @Serializable
    @SerialName("SEND")
    data class Send(
        val conversationId: String,
        val senderId: String,
        val idempotencyKey: String,
        val body: String,
    ) : ClientCommand()

    @Serializable
    @SerialName("HISTORY")
    data class History(val conversationId: String, val accountId: String, val after: Long) : ClientCommand()

    @Serializable
    @SerialName("ACK")
    data class Ack(val conversationId: String, val accountId: String, val sequence: Long) : ClientCommand()

    @Serializable
    @SerialName("RECONNECT")
    data class Reconnect(val conversationId: String, val accountId: String, val after: Long) : ClientCommand()
}

@Serializable
data class WireMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val sequence: Long,
    val idempotencyKey: String,
    val body: String,
)

@Serializable
sealed class ServerPayload {
    @Serializable
    @SerialName("SEND_ACK")
    data class SendAck(val message: WireMessage) : ServerPayload()

    @Serializable
    @SerialName("HISTORY")
    data class History(val messages: List<WireMessage>, val cursor: Long? = null) : ServerPayload()

    @Serializable
    @SerialName("ACK")
    data class Ack(val acknowledgedThrough: Long) : ServerPayload()

    @Serializable
    @SerialName("ERROR")
    data class Error(val code: String, val message: String? = null) : ServerPayload()
}

@Serializable
data class ServerEnvelope(
    val version: Int,
    val id: String,
    val payload: ServerPayload,
)

internal val realtimeJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = false
}

fun encodeClientCommand(id: String, command: ClientCommand): String {
    val commandObject = realtimeJson.encodeToJsonElement(ClientCommand.serializer(), command).jsonObject
    return realtimeJson.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("version", REALTIME_PROTOCOL_VERSION)
        put("id", id)
        commandObject.forEach { (key, value) -> put(key, value) }
    })
}

fun decodeServerEnvelope(value: String): ServerEnvelope =
    realtimeJson.decodeFromString(ServerEnvelope.serializer(), value)
