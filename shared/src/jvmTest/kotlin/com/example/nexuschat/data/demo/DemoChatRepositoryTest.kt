package com.example.nexuschat.data.demo

import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.data.model.ChatRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class DemoChatRepositoryTest {
    @Test
    fun unknown_session_has_no_messages() = runBlocking {
        val repository = DemoChatRepository()

        val messages = repository.observeMessages("unknown-session").first()

        assertEquals(emptyList(), messages)
    }

    @Test
    fun persisted_turn_is_available_from_the_same_session() = runBlocking {
        val repository = DemoChatRepository()
        val user = ChatMessage.user("Remember this", id = "user-1")
        val assistant = ChatMessage.assistant("I will remember this", modelId = "gpt-4o-mini", id = "assistant-1")

        repository.upsertMessage(DemoChatRepository.DEMO_SESSION_ID, user)
        repository.upsertMessage(DemoChatRepository.DEMO_SESSION_ID, assistant)

        val messages = repository.observeMessages(DemoChatRepository.DEMO_SESSION_ID).first()

        assertEquals(ChatRole.USER, messages[messages.lastIndex - 1].role)
        assertEquals("Remember this", messages[messages.lastIndex - 1].content)
        assertEquals("I will remember this", messages.last().content)
    }
}
