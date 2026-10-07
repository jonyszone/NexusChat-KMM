package com.example.nexuschat.domain.messenger

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ConversationType { DIRECT, GROUP }

@Serializable
enum class ConversationMemberRole { OWNER, ADMIN, MEMBER }

@Serializable
data class User(
    val id: String,
    val handle: String? = null,
    val displayName: String,
    val avatarUrl: String? = null,
)

@Serializable
data class Device(
    val id: String,
    val userId: String,
    val label: String? = null,
    val createdAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long? = null,
    val revoked: Boolean = false,
)

@Serializable
data class Conversation(
    val id: String,
    val type: ConversationType,
    val title: String? = null,
    val createdAtEpochMillis: Long,
    val revision: Long = 0,
)

@Serializable
data class ConversationMember(
    val conversationId: String,
    val userId: String,
    val role: ConversationMemberRole = ConversationMemberRole.MEMBER,
    val joinedAtEpochMillis: Long,
)

@Serializable
enum class MessageKind { TEXT, MEDIA, SYSTEM, ASSISTANT }

@Serializable
enum class DeliveryState { PENDING, SENDING, SENT, DELIVERED, READ, FAILED }

@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val clientMessageId: String,
    val idempotencyKey: String,
    val kind: MessageKind,
    val body: String,
    val createdAtEpochMillis: Long,
    val sequence: Long? = null,
    val serverMessageId: String? = null,
    val deliveryState: DeliveryState = DeliveryState.PENDING,
    val failureCode: String? = null,
    val assistantGenerated: Boolean = false,
)

@Serializable
data class MessageOrderingKey(
    val conversationId: String,
    val sequence: Long,
    val messageId: String,
)

@Serializable
sealed class OutboxOperation {
    abstract val operationId: String
    abstract val conversationId: String
    abstract val idempotencyKey: String

    @Serializable
    @SerialName("send_message")
    data class SendMessage(
        override val operationId: String,
        override val conversationId: String,
        override val idempotencyKey: String,
        val message: Message,
    ) : OutboxOperation()

    @Serializable
    @SerialName("acknowledge_message")
    data class AcknowledgeMessage(
        override val operationId: String,
        override val conversationId: String,
        override val idempotencyKey: String,
        val messageId: String,
        val state: DeliveryState,
    ) : OutboxOperation()
}

@Serializable
enum class PresenceStatus { ONLINE, AWAY, OFFLINE, UNKNOWN }

@Serializable
data class Presence(
    val userId: String,
    val status: PresenceStatus,
    val observedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long? = null,
)

@Serializable
data class Typing(
    val conversationId: String,
    val userId: String,
    val isTyping: Boolean,
    val observedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

@Serializable
enum class CallSignalingState {
    IDLE, INVITING, RINGING, CONNECTING, ACTIVE, RECONNECTING, ENDED, DECLINED, MISSED, FAILED
}

@Serializable
enum class CallMediaType { AUDIO, VIDEO }

@Serializable
data class Call(
    val id: String,
    val conversationId: String,
    val initiatorUserId: String,
    val mediaType: CallMediaType,
    val state: CallSignalingState = CallSignalingState.IDLE,
    val revision: Long = 0,
    val startedAtEpochMillis: Long? = null,
    val endedAtEpochMillis: Long? = null,
    val terminalReason: String? = null,
)

@Serializable
sealed class CallSignal {
    abstract val callId: String
    abstract val revision: Long

    @Serializable
    @SerialName("invite")
    data class Invite(override val callId: String, override val revision: Long) : CallSignal()

    @Serializable
    @SerialName("accept")
    data class Accept(override val callId: String, override val revision: Long) : CallSignal()

    @Serializable
    @SerialName("reject")
    data class Reject(override val callId: String, override val revision: Long, val reason: String? = null) : CallSignal()

    @Serializable
    @SerialName("sdp")
    data class Sdp(override val callId: String, override val revision: Long, val description: String) : CallSignal()

    @Serializable
    @SerialName("ice")
    data class Ice(override val callId: String, override val revision: Long, val candidate: String) : CallSignal()

    @Serializable
    @SerialName("hangup")
    data class Hangup(override val callId: String, override val revision: Long, val reason: String? = null) : CallSignal()
}
