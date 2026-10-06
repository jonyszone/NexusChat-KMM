# NexusChat KMP

**NexusChat** is a cross‑platform, client‑side Bring‑Your‑Own‑Key (BYOK) AI chat application built with **Kotlin Multiplatform (KMP)** and **Compose Multiplatform**.

---

## 1. Core Architecture

- **Tech Stack:** Kotlin Multiplatform, Ktor Client (with SSE plugin), Kotlin Coroutines & Flows, Compose Multiplatform for UI, SQLDelight for local chat persistence.
- **Privacy‑First (BYOK):** API keys (OpenAI, Claude, Gemini, DeepSeek) are stored encrypted strictly on the client device using native secure storage mechanisms via KMP abstractions.

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

## 3. Project Module Layout

```
NexusChat-KMM/
├─ gradle/
├─ build.gradle.kts (root)
├─ settings.gradle
├─ gradle.properties
├─ shared/                     # common KMP source set
│  └─ src/
│     └─ commonMain/
│        └─ kotlin/
│           └─ com/example/nexuschat/
│              ├─ data/
│              │  ├─ model/            ChatMessage.kt, AiModel.kt
│              │  ├─ network/          KtorLlmStreamingClient.kt
│              │  └─ persistence/      SQLDelight schema + DAOs
│              ├─ domain/
│              │  └─ repository/      ChatRepository.kt, ChatStorage expect/actual
│              └─ ui/
│                 └─ ChatScreen.kt    Compose Multiplatform main chat view
├─ androidApp/                 # Android application module
│  └─ src/
│     └─ main/
│        ├─ AndroidManifest.xml   (label = "@string/app_name", package = "shafi.example.nexuschat")
│        ├─ kotlin/
│        │   └─ com/example/nexuschat/android/
│        │       ├─ AesKeyStoreProvider.kt   (EncryptedSharedPreferences + Keystore)
│        │       └─ SqlDelightChatStorage.kt (SQLDelight actual)
│        └─ res/
│           └─ values/strings.xml   (app_name = "NexusChat")
├─ iosApp/                     # iOS application module (iOSX64 / iosArm64)
│  └─ src/
│     ├─ iosMain/
│     │   └─ kotlin/
│     │       └─ com/example/nexuschat/ios/
│     │           └─ KeychainProvider.kt   (Apple Keychain bridge)
│     └─ iosArm64/
│        └─ kotlin/
│           └─ com/example/nexuschat/ios/     (same source set, different ABI)
│  └─ build.gradle.kts (depends on :shared)
└─ desktopApp/                # Desktop (macOS / Windows / Linux) module
   └─ src/
      └─ desktopMain/
         └─ kotlin/
            └─ com/example/nexuschat/desktop/
                └─ OsKeyringProvider.kt   (OS native keyring / encrypted file fallback)
      └─ build.gradle.kts (depends on :shared)
```

All shared Kotlin code lives in `shared/src/commonMain`; Android, iOS and Desktop modules only add platform‑specific entry points and Gradle plugins.

---

## 4. Core Source Files (already implemented)

| File | Purpose |
|------|---------|
| `shared/src/commonMain/kotlin/com/example/nexuschat/data/model/ChatMessage.kt` | `ChatMessage(id, role, content, timestampEpochMillis)` + `ChatRole` enum, `@Serializable` |
| `shared/src/commonMain/kotlin/com/example/nexuschat/data/model/AiModel.kt` | `LlmProvider` enum + `AiModel(id, displayName, provider)` + `AvailableModels.all` for the UI switcher |
| `shared/src/commonMain/kotlin/com/example/nexuschat/data/network/KtorLlmStreamingClient.kt` | Unified SSE streaming client (`streamChat()`) – POST + manual `data:` parsing; providers: OpenAI, Anthropic, Gemini, DeepSeek. |
| `shared/src/commonMain/kotlin/com/example/nexuschat/domain/repository/ChatRepository.kt` | `expect/actual` `ChatStorage` interface + `OfflineFirstChatRepository` that wires the LLM client + persistence |
| `shared/src/commonMain/kotlin/com/example/nexuschat/presentation/ChatViewModel.kt` | `ChatUiState` `StateFlow`, `send()`, `switchModel()`, `cancel()`, `MissingApiKeyException` handling |
| `shared/src/commonMain/kotlin/com/example/nexuschat/ui/ChatScreen.kt` | Full Compose screen: model picker, `LazyColumn` of `MessageBubble`s, `StreamingBubble` with cursor, `MarkdownLite` for fenced code blocks, `CopyButton` via clipboard, `InputBar` with Send/Stop |

---

## 5. BYOK Storage (expect/actual)

- **Shared:** `ApiKeyProvider` interface (`keyFor(provider)`) and `ChatStorage` interface.
- **Android:** `AesKeyStoreProvider` → `EncryptedSharedPreferences` + Android Keystore.
- **iOS:** `KeychainProvider` → Apple Keychain via tiny Swift bridge.
- **Desktop:** `OsKeyringProvider` → macOS Keychain / Windows Credential Manager / encrypted file fallback.

---

## 6. Getting Started

1. **Compile the common module**  
   ```bash
   ./gradlew :shared:compileKotlinJvm   # or the specific target
   ```
2. **Add the platform‑specific actual implementations** (see the files above).  
3. **Run the app** on your desired target (Android Studio, Xcode via the iOS KMP template, or `./gradlew run` for desktop).  

---

## 7. Next Steps (recommended)

- Secure‑key storage integration per platform (already scaffolded).  
- SQLDelight schema & DAO generation for chat‑session persistence.  
- Unit‑test the delta parsers (`parseOpenAiDelta`, `parseAnthropicDelta`, `parseGeminiDelta`).  
- Add CI pipelines (GitHub Actions) that build Android, iOS, and Desktop targets.  
- Compose-based `ChatScreen` is implemented in the shared module and wired to the Android entry point.

---

*Feel free to open issues or contribute! The goal is a fully functional, privacy‑first AI chat that runs everywhere Kotlin Multiplatform does.*