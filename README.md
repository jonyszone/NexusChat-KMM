# NexusChat KMP

**NexusChat** is a Kotlin Multiplatform (KMP) and Compose Multiplatform AI chat application. The current Android target is demo-functional; production BYOK storage, persistence, and real transport are being integrated incrementally.

---

## 1. Core Architecture

- **Tech Stack:** Kotlin Multiplatform, Ktor Client, Kotlin Coroutines & Flows, Compose Multiplatform for UI.
- **Planned production layers:** encrypted BYOK storage, SQLDelight local persistence, and a real sync/message transport behind repository interfaces.

---

## 2. Core Functional Requirements

### A. Ktor Unified Streaming Engine
- Multi‑provider HTTP streaming client using `Ktor Client` and its SSE plugin.
- Server‑Sent Events from:
  - OpenAI API (`/v1/chat/completions`)
  - Anthropic Messages API (`/v1/messages`)
  - Google Gemini REST API (`generateContent?alt=sse`)
  - DeepSeek API
- Convert raw SSE events into a unified `Flow<String>` token stream for the UI.

### B. Shared Chat Engine & UI
- **Model Switcher:** Top toolbar to switch models mid‑conversation.
- **Streaming Response UI:** Render tokens smoothly as they arrive using Compose Multiplatform.
- **Local Persistence:** Save chat sessions, system personas, and custom prompts using SQLDelight.
- **Rich Text:** Support Markdown formatting, code‑block syntax highlighting, and copy actions.

---

## 3. Current Project Module Layout

```
NexusChat-KMM/
├─ gradle/
├─ app/                        # Android application entry point
├─ shared/                     # shared KMP module and Compose UI
│  └─ src/commonMain/kotlin/com/example/nexuschat/
│     ├─ data/                 # models, demo repository, Ktor client
│     ├─ domain/               # repository contracts and storage boundary
│     ├─ presentation/         # shared ViewModel and UI state
│     └─ ui/                   # Compose screens/components
├─ docs/                       # architecture notes and demo fixtures
└─ .github/workflows/          # Android CI
```

All shared Kotlin code lives in `shared/src/commonMain`; Android, iOS and Desktop modules only add platform‑specific entry points and Gradle plugins.

---

## 4. Current Core Source Files

| File | Purpose |
|------|---------|
| `shared/src/commonMain/kotlin/com/example/nexuschat/data/model/ChatMessage.kt` | `ChatMessage(id, role, content, timestampEpochMillis)` + `ChatRole` enum, `@Serializable` |
| `shared/src/commonMain/kotlin/com/example/nexuschat/data/model/AiModel.kt` | `LlmProvider` enum + `AiModel(id, displayName, provider)` + `AvailableModels.all` for the UI switcher |
| `shared/src/commonMain/kotlin/com/example/nexuschat/data/network/KtorLlmStreamingClient.kt` | Unified SSE streaming client (`streamChat()`) – POST + manual `data:` parsing; providers: OpenAI, Anthropic, Gemini, DeepSeek. |
| `shared/src/commonMain/kotlin/com/example/nexuschat/domain/repository/ChatRepository.kt` | `expect/actual` `ChatStorage` interface + `OfflineFirstChatRepository` that wires the LLM client + persistence |
| `shared/src/commonMain/kotlin/com/example/nexuschat/presentation/ChatViewModel.kt` | `ChatUiState` `StateFlow`, `send()`, `switchModel()`, `cancel()`, `MissingApiKeyException` handling |
| `shared/src/commonMain/kotlin/com/example/nexuschat/ui/ChatScreen.kt` | Full Compose screen: model picker, `LazyColumn` of `MessageBubble`s, `StreamingBubble` with cursor, `MarkdownLite` for fenced code blocks, `CopyButton` via clipboard, `InputBar` with Send/Stop |

---

## 5. Planned BYOK Storage (not wired yet)

- **Shared:** `ApiKeyProvider` interface (`keyFor(provider)`) and `ChatStorage` interface.
Android Keystore, Apple Keychain, and desktop credential storage are planned platform implementations. The current Android entry point still uses `DemoChatRepository` and does not expose API-key settings.

---

## 6. Getting Started

1. **Compile the common module**  
   ```bash
   ./gradlew :shared:compileKotlinJvm   # or the specific target
   ```
2. **Run the Android demo** from Android Studio or with `./gradlew :app:installDebug` on a connected device.
3. **Production setup:** add platform-specific secure storage, persistence, and real transport before enabling BYOK mode.

---

## 7. Next Steps (recommended)

- Secure-key storage integration per platform.
- SQLDelight schema and DAO generation for chat-session persistence.
- Unit‑test the delta parsers (`parseOpenAiDelta`, `parseAnthropicDelta`, `parseGeminiDelta`).  
- Add CI pipelines (GitHub Actions) that build Android, iOS, and Desktop targets.  
- Compose-based `ChatScreen` is implemented in the shared module and wired to the Android demo entry point.

---

*Feel free to open issues or contribute! The goal is a fully functional, privacy‑first AI chat that runs everywhere Kotlin Multiplatform does.*