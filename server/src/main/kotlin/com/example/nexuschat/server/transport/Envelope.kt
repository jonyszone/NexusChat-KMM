package com.example.nexuschat.server.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class EnvelopeType { SEND_MESSAGE, MESSAGE_ACK, PRESENCE, TYPING, CALL_SIGNAL }

@Serializable
sealed interface ClientEnvelope {
    @Serializable
    @SerialName("SEND_MESSAGE")
    data class SendMessage(
        val conversationId: String,
        val clientMessageId: String,
        val idempotencyKey: String,
        val text: String,
    ) : ClientEnvelope

    @Serializable
    @SerialName("PRESENCE")
    data class Presence(val status: String) : ClientEnvelope

    @Serializable
    @SerialName("TYPING")
    data class Typing(val conversationId: String, val isTyping: Boolean) : ClientEnvelope

    @Serializable
    @SerialName("CALL_SIGNAL")
    data class CallSignal(
        val conversationId: String,
        val callId: String,
        val kind: String,
        val payload: JsonElement,
    ) : ClientEnvelope
}

@Serializable
sealed interface ServerEnvelope {
    @Serializable
    @SerialName("MESSAGE_ACK")
    data class MessageAck(
        val clientMessageId: String,
        val idempotencyKey: String,
        val serverMessageId: String,
        val conversationId: String,
        val sequence: Long,
        val duplicate: Boolean,
    ) : ServerEnvelope

    @Serializable
    @SerialName("PRESENCE")
    data class Presence(val userId: String, val status: String) : ServerEnvelope

    @Serializable
    @SerialName("TYPING")
    data class Typing(val userId: String, val conversationId: String, val isTyping: Boolean) : ServerEnvelope

    @Serializable
    @SerialName("CALL_SIGNAL")
    data class CallSignal(val fromUserId: String, val conversationId: String, val callId: String, val kind: String, val payload: JsonElement) : ServerEnvelope
}
