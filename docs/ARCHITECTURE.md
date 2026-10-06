# NexusChat Architecture Direction

Status: research checkpoint — October 6, 2026

## Decision

Use a shared Kotlin Multiplatform application with shared Compose UI, shared MVVM presentation state, feature-scoped repositories, and an offline-first local data source. Keep the LLM/network implementation behind interfaces so demo data can be replaced by real services without changing screens.

This follows the useful shape of John O'Reilly's `PeopleInSpace` KMP sample: a shared domain/data core with platform clients and multiple UI targets, rather than putting Android-only assumptions in `commonMain`. It also follows the official Kotlin guidance that shared ViewModel + UI, shared repository/data only, or a mixed model are all valid; this project should start with shared ViewModel/UI because the Android and iOS chat behavior should be identical.

## Target modules

```text
NexusChat-KMM/
├── app/                         # Android entry point and platform wiring
├── shared/
│   └── src/
│       ├── commonMain/
│       │   └── kotlin/com/example/nexuschat/
│       │       ├── core/         # Result, dispatchers, clock, IDs, logging
│       │       ├── data/         # DTOs, demo source, network source, mappers
│       │       ├── domain/       # entities, repository contracts, use cases
│       │       ├── presentation/ # ViewModels, UiState, UiEvent, intents
│       │       └── ui/           # shared Compose screens/components
│       └── androidMain/          # Android storage, secure key store, push hooks
└── docs/
    ├── ARCHITECTURE.md
    └── demo/demo_chat.json
```

## Feature boundaries

- `chat`: sessions, messages, streaming, retry, cancel, reactions, read state
- `inbox`: chat list, unread count, archive, search, draft previews
- `profile`: identity, about, avatar, linked devices
- `updates`: status/channel feed
- `calls`: call history and future call transport
- `settings`: provider keys, privacy, notifications, appearance

Each feature owns its `Contract` (state + intent), ViewModel, use cases, repository interface, and screen. UI does not call Ktor, SQLDelight, crypto, or Android APIs directly.

## MVVM / unidirectional flow

```text
User action -> Screen intent -> ViewModel -> UseCase -> Repository
                                             |
                                             v
                                      StateFlow<UiState>
                                             |
                                             v
                                           Screen
```

Rules:

1. A screen renders only `UiState` and emits typed intents.
2. ViewModels never expose mutable state or transport DTOs.
3. Repositories are the only boundary for persistence/network.
4. One-shot effects use a `SharedFlow<UiEvent>`; durable screen state uses `StateFlow`.
5. Loading, empty, content, offline, error, and retry are explicit states.
6. A message is written locally before network delivery; delivery state changes later.

## Demo mode

The first implementation uses a deterministic JSON-backed fake repository. It must support:

- seeded chat sessions and messages
- local send that immediately appends a pending message
- simulated assistant response
- retry and cancel
- navigation between inbox, chat, profile, updates, communities, calls, and settings

The demo repository is intentionally replaceable with:

- SQLDelight local database
- Ktor WebSocket/message transport
- LLM streaming transport
- secure key storage

## WhatsApp-inspired system design direction

Do not copy WhatsApp proprietary implementation. Use the observable product behaviors as requirements:

- local-first message timeline
- durable message IDs and idempotency keys
- outbox for unsent messages
- retry-safe acknowledgements: pending -> sent -> delivered -> read
- reconnect and resync using a cursor/high-water mark
- encrypted payloads and keys kept on clients when real messaging is added
- media metadata separate from encrypted blob transfer
- push notifications as a wake-up hint, not the source of truth
- one conversation stream with ordered local rendering
- conflict handling for edits, deletes, reactions, and multi-device state

## Real backend later

```text
Compose UI
  -> shared ViewModel
  -> use cases
  -> repository
  -> local database (source of truth)
  -> sync engine / outbox
  -> WebSocket or HTTPS API
  -> message gateway, fanout, push, media service
```

The server must never be assumed to be the local UI source of truth. The client owns an outbox and reconciles server acknowledgements. For a future secure messenger, adopt a reviewed Signal Protocol implementation rather than designing cryptography inside the UI project.

## Research references

- Kotlin Multiplatform ViewModel guidance: https://kotlinlang.org/docs/multiplatform/compose-viewmodel.html
- Kotlin Multiplatform navigation guidance: https://kotlinlang.org/docs/multiplatform/compose-navigation.html
- John O'Reilly PeopleInSpace sample: https://github.com/joreilly/PeopleInSpace
- John O'Reilly KMP libraries talk: https://resources.jetbrains.com/storage/products/kotlinconf-2024/may-23/John%20O%E2%80%99Reilly%20-%20Hitchhiker%E2%80%99s%20Guide%20to%20Kotlin%20Multiplatform%20Libraries.pdf
- WhatsApp system behavior reference: https://seroze.github.io/whatsapp-system-design/
- WhatsApp/Signal E2EE research: https://arxiv.org/abs/2209.11198

These are architecture references, not claims that NexusChat currently implements production WhatsApp security or scale.
