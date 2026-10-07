package com.example.nexuschat.domain.repository

import com.example.nexuschat.data.model.AvailableModels
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.data.model.ChatMode
import com.example.nexuschat.data.model.TurnState
import com.example.nexuschat.testutil.mockStreamingClient
import com.example.nexuschat.testutil.sse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineFirstChatRepositoryTest {

    private fun repository(storage: ChatStorage = InMemoryChatStorage()) =
        OfflineFirstChatRepository(
            llm = mockStreamingClient(sseBody = sse("""data: {"choices":[{"delta":{"content":"hi"}}]}""", "data: [DONE]")),
            storage = storage,
            mode = ChatMode.BYOK
        )

    @Test
    fun stream_reply_delegates_to_the_transport() = runBlocking {
        val tokens = repository().streamReply(AvailableModels.Gpt4oMini, listOf(ChatMessage.user("hi"))).toList()

        assertEquals(listOf("hi"), tokens)
    }

    @Test
    fun begin_turn_persists_user_and_placeholder_then_settles_in_place() = runBlocking {
        val storage = InMemoryChatStorage()
        val repository = repository(storage)
        repository.createSession(
            ChatSession("s1", "Chat", "gpt-4o-mini", mode = ChatMode.BYOK, createdAt = 1, updatedAt = 2)
        )

        val user = ChatMessage.user("hello", id = "u1", turnId = "t1", attemptId = "a1")
        val placeholder = ChatMessage.assistant(
            "", modelId = "gpt-4o-mini", id = "m1", turnId = "t1", attemptId = "a1", state = TurnState.PENDING
        )
        repository.beginTurn("s1", user, placeholder, AvailableModels.Gpt4oMini, "sys", 10L)

        // Pre-request write already stores the user message and placeholder.
        val afterBegin = repository.observeMessages("s1").first()
        assertEquals(listOf("u1", "m1"), afterBegin.map { it.id })
        assertEquals(TurnState.PENDING, afterBegin[1].state)

        repository.upsertMessage("s1", placeholder.copy(content = "done", state = TurnState.COMPLETED))

        val settled = repository.observeMessages("s1").first()
        assertEquals(2, settled.size) // no duplicate
        assertEquals("done", settled[1].content)
    }

    @Test
    fun recover_interrupted_marks_active_turns() = runBlocking {
        val storage = InMemoryChatStorage()
        val repository = repository(storage)
        repository.createSession(
            ChatSession("s1", "Chat", "gpt-4o-mini", mode = ChatMode.BYOK, createdAt = 1, updatedAt = 2)
        )
        repository.upsertMessage(
            "s1",
            ChatMessage.assistant("part", id = "m1", turnId = "t1", attemptId = "a1", state = TurnState.STREAMING)
        )

        repository.recoverInterrupted("s1")

        assertEquals(TurnState.INTERRUPTED, repository.observeMessages("s1").first().single().state)
    }

    @Test
    fun clear_and_delete_remove_expected_rows() = runBlocking {
        val storage = InMemoryChatStorage()
        val repository = repository(storage)
        repository.createSession(
            ChatSession("s1", "Chat", "gpt-4o-mini", mode = ChatMode.BYOK, createdAt = 1, updatedAt = 2)
        )
        repository.upsertMessage("s1", ChatMessage.user("hello", id = "u1"))

        repository.clearMessages("s1")
        assertTrue(repository.observeMessages("s1").first().isEmpty())
        assertEquals(1, repository.observeSessions().first().size)

        repository.deleteSession("s1")
        assertTrue(repository.observeSessions().first().isEmpty())
    }

    @Test
    fun mode_is_byok() {
        assertEquals(ChatMode.BYOK, repository().mode)
    }
}
