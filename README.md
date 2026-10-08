# NexusChat KMP

**NexusChat** is an Android-first Kotlin Multiplatform (KMP) messenger in development,
with an optional Demo/BYOK AI assistant. The Android first screen supports
authenticated Rust-backend messaging, local history, and durable retries. See
[the authenticated messaging slice](docs/AUTHENTICATED_MESSAGING_SLICE.md) for setup,
verification, and remaining work.

---

## 1. Core Architecture

- **Tech Stack:** Kotlin Multiplatform, Ktor Client, Kotlin Coroutines & Flows, Compose Multiplatform for UI.
- **Implemented layers:** Android Keystore-backed session/BYOK storage, account-scoped
  SQLDelight messaging/outbox persistence, foreground WebSocket change notifications
  with authenticated HTTP synchronization/recovery, and
  separate assistant/provider repositories. E2EE, push, media, and calls remain unfinished.

---

## 2. Core Functional Requirements

### A. Ktor Unified Streaming Engine
- Multi‑provider HTTP streaming client using `Ktor Client` and a bounded manual SSE framer.
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

## 5. BYOK Storage and privacy boundary

- Android keys are encrypted with Android Keystore and are never placed in SavedStateHandle, the database, or logs. Chat history is local SQLite data and provider requests include the selected conversation context.
- The current configured targets are Android and JVM tests. iOS/Desktop secure-storage adapters, cloud sync, E2EE, and release signing remain future work.

---

## 6. Getting Started

1. **Compile the common module**  
   ```bash
   ./gradlew :shared:compileKotlinJvm   # or the specific target
   ```
2. **Run the Android app** from Android Studio or with `./gradlew :app:installDebug` on a connected device. Sign in/register against the Rust backend; debug emulator builds default to `http://10.0.2.2:3000`.
3. **Optional assistant:** open Assistant from the toolbar, then choose Demo or BYOK in Settings. Provider credentials are independent of messenger accounts.
4. **Local Rust interoperability:** with JDK 17, run `bash scripts/verify-rust-interop.sh`. It verifies real client/router contracts over process I/O, including restart, without requiring network sockets or PostgreSQL.

---

## 7. Next Steps (recommended)

- Add device lifecycle/navigation/backup verification when an Android test device is available.
- Validate provider contracts against authorized live providers; parser tests do not establish model availability.
- Add CI emulator matrix and protected live-provider workflow without exposing credentials to fork builds.
- Compose-based `ChatScreen` is implemented in the shared module and wired to the Android lifecycle-owned ViewModel.

---

*Feel free to open issues or contribute! The goal is a fully functional, privacy‑first AI chat that runs everywhere Kotlin Multiplatform does.*
