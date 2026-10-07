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
    val modelId: String? = null,
    /** Durable turn identity shared by the user prompt and its assistant result. */
    val turnId: String? = null,
    /** Identity of a single attempt at producing the assistant result for [turnId]. */
    val attemptId: String? = null,
    /** Stable per-session ordering key; assigned by storage, never by the UI. */
    val sequence: Long = 0L,
    /** Lifecycle state; assistant placeholders start PENDING. */
    val state: TurnState = TurnState.COMPLETED,
    /** Provider that produced/serves this message (provenance). */
    val provider: LlmProvider? = null,
    /** Sanitized error category for FAILED turns (never a raw provider body). */
    val errorCategory: String? = null
) {
    val isUser: Boolean get() = role == ChatRole.USER

    companion object {
        @OptIn(ExperimentalTime::class)
        fun now(): Long = Clock.System.now().toEpochMilliseconds()

        fun user(
            content: String,
            id: String = randomId(),
            turnId: String? = null,
            attemptId: String? = null,
            timestamp: Long = now()
        ): ChatMessage =
            ChatMessage(
                id = id,
                role = ChatRole.USER,
                content = content,
                timestampEpochMillis = timestamp,
                turnId = turnId,
                attemptId = attemptId,
                state = TurnState.COMPLETED
            )

        fun assistant(
            content: String,
            modelId: String? = null,
            id: String = randomId(),
            turnId: String? = null,
            attemptId: String? = null,
            state: TurnState = TurnState.COMPLETED,
            provider: LlmProvider? = null,
            errorCategory: String? = null,
            timestamp: Long = now()
        ): ChatMessage =
            ChatMessage(
                id = id,
                role = ChatRole.ASSISTANT,
                content = content,
                timestampEpochMillis = timestamp,
                modelId = modelId,
                turnId = turnId,
                attemptId = attemptId,
                state = state,
                provider = provider,
                errorCategory = errorCategory
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
