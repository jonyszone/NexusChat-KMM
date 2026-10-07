package com.example.nexuschat.server.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

/** Development fixture entities; they are not credential or authentication records. */
@Serializable data class Account(val id: String, val handle: String, val displayName: String)
@Serializable data class DeviceRecord(val id: String, val accountId: String, val revoked: Boolean = false)
@Serializable data class ContactRecord(val accountId: String, val contactAccountId: String)
@Serializable data class DirectConversationRecord(val id: String, val memberAccountIds: Set<String>)
@Serializable data class AccountSnapshot(val accounts: List<Account>, val devices: List<DeviceRecord>, val contacts: List<ContactRecord>, val conversations: List<DirectConversationRecord>)

interface AccountRepository {
    fun account(accountId: String): Account?
    fun contacts(accountId: String): List<Account>
    fun directConversation(firstAccountId: String, secondAccountId: String): DirectConversationRecord?
    fun isMember(accountId: String, conversationId: String): Boolean
    fun memberships(): Map<String, Set<String>>
}

/** Static users and memberships for local development only; no registration/login is performed. */
object DevelopmentAccountRepository : AccountRepository {
    private val fixture = AccountSnapshot(
        accounts = listOf(Account("alice", "alice", "Alice"), Account("bob", "bob", "Bob"), Account("mallory", "mallory", "Mallory")),
        devices = listOf(DeviceRecord("alice-device", "alice"), DeviceRecord("bob-device", "bob")),
        contacts = listOf(ContactRecord("alice", "bob"), ContactRecord("bob", "alice")),
        conversations = listOf(DirectConversationRecord("c", setOf("alice", "bob")))
    )
    private val repository by lazy { RepositoryData(fixture) }
    fun snapshot(): AccountSnapshot = fixture
    override fun account(accountId: String) = repository.account(accountId)
    override fun contacts(accountId: String): List<Account> = repository.contacts(accountId)
    override fun directConversation(firstAccountId: String, secondAccountId: String): DirectConversationRecord? = repository.directConversation(firstAccountId, secondAccountId)
    override fun isMember(accountId: String, conversationId: String): Boolean = repository.isMember(accountId, conversationId)
    override fun memberships() = repository.memberships()
}

private class RepositoryData(private val data: AccountSnapshot) : AccountRepository {
    override fun account(accountId: String) = data.accounts.firstOrNull { it.id == accountId }
    override fun contacts(accountId: String): List<Account> = data.contacts.filter { it.accountId == accountId }.mapNotNull { account(it.contactAccountId) }
    override fun directConversation(firstAccountId: String, secondAccountId: String): DirectConversationRecord? = data.conversations.firstOrNull {
        firstAccountId != secondAccountId && firstAccountId in it.memberAccountIds && secondAccountId in it.memberAccountIds
    }
    override fun isMember(accountId: String, conversationId: String): Boolean = accountId in memberships()[conversationId].orEmpty()
    override fun memberships(): Map<String, Set<String>> = data.conversations.associate { it.id to it.memberAccountIds }
}

/** Atomic-file fixture persistence for local tests/development only. */
class FileAccountRepository(private val file: File, initial: AccountSnapshot? = null) : AccountRepository {
    private val data: RepositoryData
    init {
        if (!file.exists()) {
            requireNotNull(initial) { "initial fixture required for a new account store" }
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile ?: File("."), file.name + ".tmp")
            temp.writeText(Json.encodeToString(initial))
            check(temp.renameTo(file)) { "atomic account-store replacement failed" }
        }
        data = RepositoryData(Json.decodeFromString<AccountSnapshot>(file.readText()))
    }
    override fun account(accountId: String) = data.account(accountId)
    override fun contacts(accountId: String): List<Account> = data.contacts(accountId)
    override fun directConversation(firstAccountId: String, secondAccountId: String) = data.directConversation(firstAccountId, secondAccountId)
    override fun isMember(accountId: String, conversationId: String) = data.isMember(accountId, conversationId)
    override fun memberships() = data.memberships()
}
