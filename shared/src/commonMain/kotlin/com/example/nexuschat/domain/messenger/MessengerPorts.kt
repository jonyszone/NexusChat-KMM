package com.example.nexuschat.domain.messenger

import kotlinx.coroutines.flow.Flow

/** Transport is intentionally protocol-agnostic; a WebSocket adapter is future infrastructure. */
interface RealtimeTransport {
    val events: Flow<RealtimeEvent>
    suspend fun connect(session: DeviceSession)
    suspend fun disconnect()
    suspend fun send(operation: OutboxOperation)
}

sealed interface RealtimeEvent {
    data class MessageAccepted(val message: Message) : RealtimeEvent
    data class MessageReceipt(val messageId: String, val state: DeliveryState) : RealtimeEvent
    data class MessageReceived(val message: Message) : RealtimeEvent
    data class PresenceChanged(val presence: Presence) : RealtimeEvent
    data class TypingChanged(val typing: Typing) : RealtimeEvent
    data class CallSignalReceived(val signal: CallSignal) : RealtimeEvent
    data class CursorAdvanced(val conversationId: String, val sequence: Long) : RealtimeEvent
}

data class DeviceSession(val userId: String, val deviceId: String, val accessToken: String)

interface ContactsRepository {
    fun observeContacts(): Flow<List<User>>
    suspend fun search(query: String): List<User>
    suspend fun addContact(userId: String)
    suspend fun removeContact(userId: String)
}

interface MediaGateway {
    suspend fun createUpload(mediaType: String, sizeBytes: Long, sha256: String): UploadGrant
    suspend fun upload(grant: UploadGrant, bytes: Flow<ByteArray>): MediaUploadResult
    suspend fun download(mediaId: String): Flow<ByteArray>
}

data class UploadGrant(val mediaId: String, val uploadUrl: String, val expiresAtEpochMillis: Long)
data class MediaUploadResult(val mediaId: String, val sha256: String, val sizeBytes: Long)

interface PushGateway {
    suspend fun registerToken(deviceId: String, token: String)
    suspend fun unregisterToken(deviceId: String)
}

interface CallsGateway {
    val signals: Flow<CallSignal>
    suspend fun sendSignal(signal: CallSignal)
    suspend fun endCall(callId: String, reason: String)
}

interface MessengerRepository {
    fun observeConversations(): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<Message>>
    fun observePresence(userId: String): Flow<Presence?>
    suspend fun enqueue(operation: OutboxOperation)
    suspend fun retry(operationId: String)
    suspend fun acknowledge(messageId: String, state: DeliveryState)
}
