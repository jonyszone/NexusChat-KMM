package com.example.nexuschat.server.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val PROTOCOL_VERSION = 1

@Serializable
sealed interface ClientEnvelope { val version: Int
    @Serializable @SerialName("SEND_MESSAGE") data class SendMessage(val conversationId: String, val clientMessageId: String, val idempotencyKey: String, val text: String, override val version: Int = PROTOCOL_VERSION) : ClientEnvelope
    @Serializable @SerialName("HISTORY") data class History(val conversationId: String, val afterSequence: Long, override val version: Int = PROTOCOL_VERSION) : ClientEnvelope
    @Serializable @SerialName("PRESENCE") data class Presence(val status: String, override val version: Int = PROTOCOL_VERSION) : ClientEnvelope
    @Serializable @SerialName("TYPING") data class Typing(val conversationId: String, val isTyping: Boolean, override val version: Int = PROTOCOL_VERSION) : ClientEnvelope
    @Serializable @SerialName("CALL_SIGNAL") data class CallSignal(val conversationId: String, val callId: String, val kind: String, val payload: JsonElement, override val version: Int = PROTOCOL_VERSION) : ClientEnvelope
}

@Serializable
sealed interface ServerEnvelope { val version: Int
    @Serializable @SerialName("MESSAGE_ACK") data class MessageAck(val clientMessageId: String, val idempotencyKey: String, val serverMessageId: String, val conversationId: String, val sequence: Long, val duplicate: Boolean, override val version: Int = PROTOCOL_VERSION) : ServerEnvelope
    @Serializable @SerialName("ERROR") data class Error(val code: String, val message: String, val clientMessageId: String? = null, val idempotencyKey: String? = null, override val version: Int = PROTOCOL_VERSION) : ServerEnvelope
    @Serializable @SerialName("HISTORY") data class History(val conversationId: String, val afterSequence: Long, val messages: List<MessageAck>, override val version: Int = PROTOCOL_VERSION) : ServerEnvelope
    @Serializable @SerialName("PRESENCE") data class Presence(val userId: String, val status: String, override val version: Int = PROTOCOL_VERSION) : ServerEnvelope
    @Serializable @SerialName("TYPING") data class Typing(val userId: String, val conversationId: String, val isTyping: Boolean, override val version: Int = PROTOCOL_VERSION) : ServerEnvelope
    @Serializable @SerialName("CALL_SIGNAL") data class CallSignal(val fromUserId: String, val conversationId: String, val callId: String, val kind: String, val payload: JsonElement, override val version: Int = PROTOCOL_VERSION) : ServerEnvelope
}
