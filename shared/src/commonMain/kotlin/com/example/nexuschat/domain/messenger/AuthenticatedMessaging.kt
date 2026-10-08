package com.example.nexuschat.domain.messenger

import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow

@Serializable
data class MessengerSession(
    val serverUrl: String,
    val accountId: String,
    val sessionId: String,
    val accessToken: String,
    val expiresAt: Long,
) {
    override fun toString(): String = "MessengerSession(accountId=$accountId, credentials=redacted)"
    val identity: MessengerIdentity get() = MessengerIdentity(serverUrl, accountId)
}

data class MessengerIdentity(val serverUrl: String, val accountId: String)

@Serializable
data class BackendConversation(val id: String, val members: Set<String>)

interface MessengerSessionStore {
    suspend fun load(): MessengerSession?
    suspend fun save(session: MessengerSession)
    suspend fun clear()
}

fun interface MessengerUpdates {
    // READY and change hints both request an authorized durable history sync.
    fun events(session: MessengerSession): Flow<Unit>
}

interface AuthenticatedMessagingApi {
    suspend fun authenticate(serverUrl: String, email: String, password: String, register: Boolean): MessengerSession
    suspend fun verify(session: MessengerSession)
    suspend fun revoke(session: MessengerSession)
    suspend fun conversations(session: MessengerSession): List<BackendConversation>
    suspend fun createConversation(session: MessengerSession, peerId: String): BackendConversation
    suspend fun send(session: MessengerSession, conversationId: String, key: String, body: String): WireMessage
    suspend fun history(session: MessengerSession, conversationId: String, after: Long): List<WireMessage>
    suspend fun acknowledge(session: MessengerSession, conversationId: String, sequence: Long): Long
}

class MessagingHttpException(val status: Int) : IllegalStateException("Messaging request failed (HTTP $status)")
class MessagingProtocolException : IllegalStateException("Invalid messaging server response")

data class CachedConversation(val id: String, val members: Set<String>, val cursor: Long)
data class PendingMessage(val key: String, val conversationId: String, val body: String, val state: String)

interface MessengerStorage {
    suspend fun conversations(identity: MessengerIdentity): List<CachedConversation>
    suspend fun saveConversations(identity: MessengerIdentity, conversations: List<BackendConversation>)
    suspend fun messages(identity: MessengerIdentity, conversationId: String): List<WireMessage>
    suspend fun mergeHistory(identity: MessengerIdentity, conversationId: String, after: Long, messages: List<WireMessage>)
    suspend fun accept(identity: MessengerIdentity, message: WireMessage)
    suspend fun enqueue(identity: MessengerIdentity, message: PendingMessage)
    suspend fun outbox(identity: MessengerIdentity): List<PendingMessage>
    suspend fun setPendingState(identity: MessengerIdentity, key: String, state: String)
    suspend fun recover(identity: MessengerIdentity)
}
