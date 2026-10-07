# NexusChat-KMM Checkpoint

Updated: October 7, 2026

## Current repository state

- Branch: `master`
- Origin: `https://github.com/jonyszone/NexusChat-KMM.git`
- Current work is uncommitted by design; no commit or push was performed.
- Planning document: `docs/NEXT_PHASE_PLAN.md`

## Implemented in this phase

- Added the first local real-messenger vertical slice under `shared/src/commonMain/kotlin/com/example/nexuschat/domain/messenger`: serializable user/device/conversation/member/message entities; explicit pending/sending/sent/delivered/read/failed delivery states; stable client/idempotency keys; optional server sequence metadata; durable send/ack outbox operations; presence and typing models; call state and invite/accept/reject/SDP/ICE/hangup signal models.
- Added protocol-neutral `MessengerRepository`, `RealtimeTransport`, contacts, media, push, and calls interfaces. They are contracts only; no server, WebSocket, push, media, or WebRTC implementation was added.
- Kept the existing AI/BYOK repository and provider feature unchanged and optional. Assistant provenance is represented separately from human delivery semantics.
- Added `docs/MESSENGER_DOMAIN_ADR.md`, documenting backend, signaling/WebRTC/TURN, and E2EE boundaries and explicitly deferring their implementations to later infrastructure/security work.
- Added JVM coverage for delivery transitions/retry, stable idempotency and duplicate suppression, per-conversation ordering, and polymorphic outbox/call-signal serialization.
- Added isolated `server/` Ktor JVM application module with `GET /healthz`, development-only authenticated-boundary `WS /v1/realtime`, typed client/server envelopes for send/ack, presence, typing, and call signaling, an in-memory user-scoped idempotency repository, and routing tests. See `server/README.md` for the explicit production TODO/blocker list.
- Database, Redis, push, media, WebRTC/STUN/TURN, and Signal E2EE remain interfaces/TODOs only; no credentials, external services, or production-security claims were added.

- Typed `NexusRoute` navigation for Inbox, Chat(sessionId), Settings, and Profile; toolbar and Android system-back now return through the same route transition.
- Session row IDs and new-session IDs are carried into Chat routes instead of Boolean detail flags.
- Android `ChatViewModel` is lifecycle-owned and created with `SavedStateHandle` through the saved-state factory.
- Selected session ID and bounded per-session composer drafts use the SavedStateHandle bridge; API keys, prompts, and provider responses are not stored in saved state.
- Explicit demo/BYOK mode, local SQLDelight session/message persistence, durable turn states, interrupted-turn recovery, and Android Keystore credential storage remain active.
- CI now installs JDK 17 plus Android SDK platform/build tools, runs separate JVM/unit/lint/debug-build gates, asserts required JVM suites and nonempty JVM reports, and uploads reports/APK artifacts on success or failure.
- README claims now describe the implemented Android/JVM scope and accurately exclude sync, E2EE, human messaging, and live-provider validation.

## Verification completed

Using `JAVA_HOME=/home/shafi/.jdks/jbr-17.0.14`:

```text
:server:test                PASS (3 backend tests: serialization, idempotency, health/WebSocket ack routing)
:shared:jvmTest             PASS (58 JVM test cases reported, including 4 new messenger-domain tests)
:app:testDebugUnitTest     PASS, NO-SOURCE
:app:lintDebug             PASS (0 errors; existing warnings remain)
:app:assembleDebug         PASS
 git diff --check           PASS
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Exact blockers and deferred verification

- The backend is a local vertical slice only: the session boundary is a constrained `X-Dev-User-Id` development stub, storage is in-memory, and no durable server acceptance, cursor repair, contacts service, media object storage, push provider, or cross-device delivery exists yet.
- Calls are models and gateway contracts only: no signaling service, WebRTC adapter, STUN/TURN credentials/operation, incoming-call push, or SFU exists yet.
- E2EE is intentionally not implemented: Signal Protocol library selection/integration, identity/device key directory, prekeys, trust UX, group rekeying, encrypted media, threat-model review, and interoperability/security review remain required before any privacy claim.
- The new JVM tests prove local state transitions, envelope serialization, in-memory idempotency, acknowledgement flow, health, and WebSocket routing only; they do not prove production authorization, delivery, push, calls, or cryptographic behavior.
- `:app:testDebugUnitTest` is still `NO-SOURCE`; no Android-local test was added because the current request's useful lifecycle/navigation/backup coverage depends on Android framework/device behavior and there is no existing app unit-test harness that can verify it meaningfully. The task compiles the Android source successfully.
- No instrumentation tests were added or run: `adb`/a test device is unavailable in this environment. Rotation, process relaunch, system-back UI, and backup/data-extraction behavior therefore remain device-gated.
- Live provider calls and release signing were intentionally not attempted. Provider contract/parser JVM tests do not prove model availability or authorized live behavior.
- CI does not run an emulator matrix yet; adding one requires a supported API/device policy and longer runner budget.
- Release signing, R8/release verification, accessibility/performance testing, sync backend, human messaging, and E2EE remain out of scope.
