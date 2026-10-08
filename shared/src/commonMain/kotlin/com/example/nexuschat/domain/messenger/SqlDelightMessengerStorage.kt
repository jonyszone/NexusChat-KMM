package com.example.nexuschat.domain.messenger

import com.example.nexuschat.db.NexusChatDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class SqlDelightMessengerStorage(private val database: NexusChatDatabase) : MessengerStorage {
    private val queries = database.messengerQueries
    private val membersSerializer = SetSerializer(String.serializer())

    override suspend fun conversations(identity: MessengerIdentity): List<CachedConversation> = withContext(Dispatchers.Default) {
        queries.selectConversations(identity.serverUrl, identity.accountId).executeAsList().map {
            CachedConversation(it.id, Json.decodeFromString(membersSerializer, it.members_json), it.cursor)
        }
    }

    override suspend fun saveConversations(identity: MessengerIdentity, conversations: List<BackendConversation>) = withContext(Dispatchers.Default) {
        database.transaction {
            conversations.forEach { conversation ->
                check(identity.accountId in conversation.members)
                val members = Json.encodeToString(membersSerializer, conversation.members)
                queries.insertConversation(identity.serverUrl, identity.accountId, conversation.id, members)
                queries.updateMembers(members, identity.serverUrl, identity.accountId, conversation.id)
            }
        }
    }

    override suspend fun messages(identity: MessengerIdentity, conversationId: String): List<WireMessage> = withContext(Dispatchers.Default) {
        queries.selectMessages(identity.serverUrl, identity.accountId, conversationId).executeAsList().map {
            WireMessage(it.id, it.conversation_id, it.sender_id, it.sequence, it.idempotency_key, it.body)
        }
    }

    override suspend fun mergeHistory(identity: MessengerIdentity, conversationId: String, after: Long, messages: List<WireMessage>) = withContext(Dispatchers.Default) {
        database.transaction {
            var cursor = after
            messages.forEach { message ->
                if (message.conversationId != conversationId || message.sequence != cursor + 1) throw MessagingProtocolException()
                writeMessage(identity, message)
                cursor = message.sequence
            }
            queries.advanceCursor(cursor, identity.serverUrl, identity.accountId, conversationId)
        }
    }

    override suspend fun accept(identity: MessengerIdentity, message: WireMessage) = withContext(Dispatchers.Default) {
        // A send ACK alone cannot advance history: earlier inbound messages may be missing.
        database.transaction { writeMessage(identity, message) }
    }

    private fun writeMessage(identity: MessengerIdentity, message: WireMessage) {
        val existing = queries.messageAtSequence(identity.serverUrl, identity.accountId, message.conversationId, message.sequence).executeAsOneOrNull()
        if (existing != null && WireMessage(existing.id, existing.conversation_id, existing.sender_id, existing.sequence, existing.idempotency_key, existing.body) != message) throw MessagingProtocolException()
        if (message.senderId == identity.accountId) {
            val pending = queries.pendingByKey(identity.serverUrl, identity.accountId, message.idempotencyKey).executeAsOneOrNull()
            if (pending != null && (pending.conversation_id != message.conversationId || pending.body != message.body)) throw MessagingProtocolException()
        }
        queries.insertMessage(identity.serverUrl, identity.accountId, message.conversationId, message.id, message.senderId, message.sequence, message.idempotencyKey, message.body)
        if (message.senderId == identity.accountId) queries.deletePending(identity.serverUrl, identity.accountId, message.idempotencyKey)
    }

    override suspend fun enqueue(identity: MessengerIdentity, message: PendingMessage) = withContext(Dispatchers.Default) {
        queries.insertPending(identity.serverUrl, identity.accountId, message.conversationId, message.key, message.body, message.state)
        Unit
    }

    override suspend fun outbox(identity: MessengerIdentity): List<PendingMessage> = withContext(Dispatchers.Default) {
        queries.selectOutbox(identity.serverUrl, identity.accountId).executeAsList().map {
            PendingMessage(it.idempotency_key, it.conversation_id, it.body, it.state)
        }
    }

    override suspend fun setPendingState(identity: MessengerIdentity, key: String, state: String) = withContext(Dispatchers.Default) {
        queries.pendingState(state, identity.serverUrl, identity.accountId, key)
        Unit
    }

    override suspend fun recover(identity: MessengerIdentity) = withContext(Dispatchers.Default) {
        queries.recoverPending(identity.serverUrl, identity.accountId)
        Unit
    }
}
