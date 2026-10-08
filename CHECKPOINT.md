# NexusChat-KMM Checkpoint

Updated: October 8, 2026

## Current repository state

- Branch: `master`
- Origin: `https://github.com/jonyszone/NexusChat-KMM.git`
- Latest local commit: `161a26a feat(shared): add authenticated realtime session wiring`
- Working tree was clean when this checkpoint was written.
- Planning document: `docs/NEXT_PHASE_PLAN.md`
- Backend repository: `/home/shafi/StudioProjects/NexusChat-server-rs`
- Backend latest commit at checkpoint time: `7646a90 feat(auth): add opt-in durable file account and session storage`

## Implemented and verified

- Shared messenger domain contracts cover users, devices, contacts, conversations, members, messages, delivery states, outbox/idempotency, presence, typing, and call signaling models.
- Version-1 Rust WebSocket envelopes, message acknowledgements, structured errors, history cursors, reconnect state, and fake-transport JVM tests are implemented.
- `KtorRustRealtimeTransport` now supports authenticated `DeviceSession` connections using `Authorization: Bearer <token>` and `X-Device-Id`, while retaining the isolated development `X-Dev-User-Id` path for fixtures.
- Authenticated-session transport fails closed when a transport cannot implement session authentication.
- Typed navigation, lifecycle-owned ViewModels, saved-state restoration, explicit Demo/BYOK mode, SQLDelight local persistence, durable turn states, interrupted-turn recovery, Android Keystore credential storage, and CI JDK 17/build gates remain active.
- Existing AI/BYOK behavior remains preserved and optional.

## Verification completed for this checkpoint

Using `JAVA_HOME=/home/shafi/.jdks/jbr-17.0.14`:

- `JAVA_HOME=/home/shafi/.jdks/jbr-17.0.14 bash ./gradlew :shared:jvmTest` passed.
- `JAVA_HOME=/home/shafi/.jdks/jbr-17.0.14 bash ./gradlew :app:assembleDebug` passed.
- `JAVA_HOME=/home/shafi/.jdks/jbr-17.0.14 bash ./gradlew :app:lintDebug` passed.
- Combined verification `:shared:jvmTest :app:assembleDebug :app:lintDebug` passed on October 8, 2026.
- Lint still reports one known deprecation for `LocalClipboardManager` plus existing warnings; no lint errors.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
- `git diff --check` passed.

## Current limitations and next starting point

- The client has authenticated realtime session wiring, but typed call-signaling operations are not yet integrated into the KMM client API.
- No live Rust staging server or authenticated end-to-end client/server test was available.
- No WebRTC media, STUN/TURN, call permissions, incoming-call handling, call history, push notifications, media storage, SFU, or E2EE exists.
- Development fixtures and `X-Dev-User-Id` are not production authentication.
- Real contact-to-contact messaging is not production-ready; backend persistence and authorization are still being developed in the Rust repository.
- `:app:testDebugUnitTest` remains `NO-SOURCE`; no instrumentation/device test was run because `adb` and a test device are unavailable.
- Live provider calls, release signing/R8, emulator CI, accessibility, and performance verification remain pending.

## Next implementation order

1. Add a typed authenticated call-signaling client adapter without claiming media support.
2. Connect authenticated account/session state to real backend routes.
3. Replace development-only message/contact storage with durable authorized backend flows.
4. Add live disposable PostgreSQL and authenticated end-to-end tests.
5. Design and review WebRTC/STUN/TURN, push, and E2EE separately before implementation.
