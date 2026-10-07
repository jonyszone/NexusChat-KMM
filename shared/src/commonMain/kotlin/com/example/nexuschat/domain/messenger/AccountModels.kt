package com.example.nexuschat.domain.messenger

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class Account(val id: String, val handle: String, val displayName: String)

@Serializable
data class AccountDevice(val id: String, val accountId: String, val revoked: Boolean = false)

@Serializable
data class Contact(val accountId: String, val contactAccountId: String)

@Serializable
data class ConversationMembership(val conversationId: String, val accountId: String)

interface AccountRepository {
    fun observeAccount(): Flow<Account?>
    suspend fun setDevelopmentAccount(account: Account?)
}

interface DirectConversationRepository {
    fun observeConversations(): Flow<List<Conversation>>
    suspend fun openDirectConversation(contactAccountId: String): Conversation
}
