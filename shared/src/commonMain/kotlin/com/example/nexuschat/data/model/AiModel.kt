package com.example.nexuschat.data.model

import kotlinx.serialization.Serializable

/**
 * Which backend serves a request. Keys are BYOK — stored only in
 * platform secure storage (Keystore / Keychain / OS credential vault)
 * and injected via ApiKeyProvider, never hardcoded.
 */
@Serializable
enum class LlmProvider {
    OPENAI,
    ANTHROPIC,
    GEMINI,
    DEEPSEEK
}

/**
 * Display model + routing info. `id` is the wire model name,
 * `provider` selects base URL / headers / SSE envelope parser.
 */
@Serializable
data class AiModel(
    val id: String,
    val displayName: String,
    val provider: LlmProvider
)

/** Curated default catalogue. UI binds its Model Switcher to [all]. */
object AvailableModels {
    val Gpt4oMini = AiModel(
        id = "gpt-4o-mini",
        displayName = "OpenAI · GPT-4o mini",
        provider = LlmProvider.OPENAI
    )
    val ClaudeHaiku = AiModel(
        id = "claude-3-haiku-20240307",
        displayName = "Claude · Haiku 3",
        provider = LlmProvider.ANTHROPIC
    )
    val GeminiFlash = AiModel(
        id = "gemini-1.5-flash",
        displayName = "Gemini · 1.5 Flash",
        provider = LlmProvider.GEMINI
    )
    val DeepSeekChat = AiModel(
        id = "deepseek-chat",
        displayName = "DeepSeek · Chat",
        provider = LlmProvider.DEEPSEEK
    )

    val all: List<AiModel> = listOf(Gpt4oMini, ClaudeHaiku, GeminiFlash, DeepSeekChat)

    fun byId(id: String): AiModel? = all.firstOrNull { it.id == id }
}