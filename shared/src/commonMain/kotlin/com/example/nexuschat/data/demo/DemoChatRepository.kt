package com.example.nexuschat.data.demo

import com.example.nexuschat.data.model.AiModel
import com.example.nexuschat.data.model.ChatMessage
import com.example.nexuschat.domain.repository.ChatRepository
import com.example.nexuschat.domain.repository.ChatSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/** Deterministic local backend used until the real sync/LLM services are connected. */
class DemoChatRepository : ChatRepository {
    private val seeded = listOf(
        ChatMessage.assistant(
            "Welcome to NexusChat. This is local demo data for the first functional build.",
            modelId = "gpt-4o-mini",
            id = "demo-welcome"
        ),
        ChatMessage.user("What can I do here?", id = "demo-question"),
        ChatMessage.assistant(
            "Try the Chats, Updates, Communities, Calls, Profile, and Settings flows. Sending a message uses the demo repository until the real backend is connected.",
            modelId = "gpt-4o-mini",
            id = "demo-answer"
        )
    )

    private val messagesBySession = mutableMapOf(
        "demo-general" to seeded.toMutableList()
    )

    override fun streamReply(model: AiModel, history: List<ChatMessage>, systemPrompt: String?): Flow<String> = flow {
        val prompt = history.lastOrNull { it.role.name == "USER" }?.content.orEmpty()
        val response = when {
            prompt.contains("hello", ignoreCase = true) || prompt.contains("hi", ignoreCase = true) ->
                "Hello from the NexusChat demo repository."
            prompt.contains("help", ignoreCase = true) ->
                "The demo supports navigation, chat state, send, streaming, retry, cancel, and local seeded content."
            else -> "Demo response received: $prompt\n\nThe repository boundary is ready for the real LLM implementation."
        }
        response.split(" ").forEach { token ->
            emit("$token ")
            delay(18)
        }
    }

    override fun observeSessions(): Flow<List<ChatSession>> = flowOf(
        listOf(ChatSession("demo-general", "NexusChat demo", "gpt-4o-mini", createdAt = 1728000000000, updatedAt = 1728000120000))
    )

    override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        flowOf(messagesBySession[sessionId]?.toList().orEmpty())

    override suspend fun ensureSession(session: ChatSession) = Unit

    override suspend fun persistTurn(sessionId: String, user: ChatMessage, assistant: ChatMessage) {
        messagesBySession.getOrPut(sessionId) { mutableListOf() }.apply {
            add(user)
            add(assistant)
        }
    }
}
