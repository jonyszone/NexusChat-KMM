package com.example.nexuschat.data.model

import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Single chat turn. Pure Kotlin — no platform APIs so it compiles
 * in commonMain (Android / iOS / Desktop).
 */
@Serializable
enum class ChatRole {
    SYSTEM,
    USER,
    ASSISTANT
}

@Serializable
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
    val timestampEpochMillis: Long,
    val modelId: String? = null
) {
    companion object {
        @OptIn(ExperimentalTime::class)
        fun now(): Long = Clock.System.now().toEpochMilliseconds()

        fun user(content: String, id: String = randomId()): ChatMessage =
            ChatMessage(
                id = id,
                role = ChatRole.USER,
                content = content,
                timestampEpochMillis = now()
            )

        fun assistant(
            content: String,
            modelId: String? = null,
            id: String = randomId()
        ): ChatMessage =
            ChatMessage(
                id = id,
                role = ChatRole.ASSISTANT,
                content = content,
                timestampEpochMillis = now(),
                modelId = modelId
            )

        fun system(content: String, id: String = randomId()): ChatMessage =
            ChatMessage(
                id = id,
                role = ChatRole.SYSTEM,
                content = content,
                timestampEpochMillis = now()
            )

        // KMP-safe id without java.util.UUID / platform APIs.
        fun randomId(): String {
            val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
            val rnd = kotlin.random.Random.Default
            return buildString(16) { repeat(16) { append(chars[rnd.nextInt(chars.length)]) } } +
                "-" + now().toString(36)
        }
    }
}