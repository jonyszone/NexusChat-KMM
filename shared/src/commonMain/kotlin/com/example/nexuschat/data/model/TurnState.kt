package com.example.nexuschat.data.model

/**
 * Explicit lifecycle of a chat turn. Persisted on the assistant message row so
 * an abandoned attempt is never mistaken for a completed answer.
 *
 * PENDING   -> accepted locally, no provider output yet
 * STREAMING -> provider stream is open and partial text may be checkpointed
 * COMPLETED -> stream finished cleanly and the answer is final
 * FAILED    -> transport / provider error, partial text (if any) retained
 * CANCELLED -> user stopped the stream
 * INTERRUPTED -> the process died mid-attempt and the turn was reconciled on reopen
 */
enum class TurnState {
    PENDING,
    STREAMING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED;

    /** True while the turn may still produce output. */
    val isActive: Boolean
        get() = this == PENDING || this == STREAMING
}

/**
 * How a session's content was produced. Selected explicitly by the user; there
 * is never a silent fallback between the two.
 *
 * DEMO -> deterministic local seeded data, no credentials, no network
 * BYOK -> real provider calls using a user-supplied key; empty means empty
 */
enum class ChatMode {
    DEMO,
    BYOK
}
