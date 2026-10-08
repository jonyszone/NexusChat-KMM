# NexusChat-KMM Checkpoint

Updated: October 8, 2026

## Local HTTPS/WSS verification

- Added opt-in `NEXUS_TEST_TLS=true` to the disposable Android network runner.
  It generates ephemeral CA/server certificates and runs a loopback-only TLS proxy;
  the real messaging flow then uses HTTPS/WSS through ADB reverse forwarding.
- Temporary CA trust exists only in instrumentation clients. Production trust and
  hostname verification remain unchanged; strict messaging mode rejects HTTP.
- All five tests passed on RMX3261 against PostgreSQL, including HTTP/WebSocket
  rejection of untrusted certificates and mismatched hostnames. Instrumentation
  assembly and lint passed; the original four-test HTTP mode also passed again.
  All temporary services, keys, and forwarding were cleaned up.
- Public production TLS deployment/renewal, two-device LAN/UI acceptance, distributed
  notification fan-out, and background push remain separate pending milestones.

## Foreground realtime delivery

- Added a bearer-authenticated read-only WebSocket change feed. The foreground
  client synchronizes authorized HTTP history immediately on READY/change hints;
  locally committed cursors and durable outbox remain authoritative.
- Added bounded/coalesced notifications, reconnect backoff, foreground/background
  lifecycle cancellation, and logout cleanup. Five-second polling remains a recovery
  fallback. Backend event delivery checks current session/membership; messaging
  command sockets revalidate before every command.
- Shared lifecycle/protocol tests, Rust event authorization/revocation/lag tests,
  full Rust tests, PostgreSQL regression tests, Android build, and lint passed.
- The expanded real-device test passed all four tests on RMX3261 against PostgreSQL:
  live recipient hints, outsider isolation, missed-history reconnect, and revoked
  feed closure. Temporary server, cluster, and forwarding were cleaned up.
- Notifications are process-local, not distributed fan-out, background push,
  delivered/read receipts, TLS acceptance, or a two-device LAN/UI workflow test.

## Authenticated messaging development

- The Android first screen now supports Rust registration/login, restored sessions,
  conversation listing/creation, sending, history recovery, refresh, and sign-out.
  The existing Demo/BYOK assistant remains separate and optional.
- Added typed bearer-authenticated Rust HTTP contracts and explicit snake_case
  realtime formatting while preserving the Kotlin development fixture format.
- Android sessions use a separate Keystore AES-GCM credential store. SQLDelight
  schema v3 adds server/account-scoped messenger history, cursors, and durable
  outbox retry/recovery; existing assistant history is preserved by migration.
- Added authorization/protocol, migration/cache, outbox, and ViewModel tests plus
  an actual KMM/Rust process-bridge interoperability test across server/client
  storage restart. It proves local file-fixture behavior, not PostgreSQL/TCP/TLS.
- `scripts/verify-rust-interop.sh` builds the sibling Rust fixture and enables the
  normally opt-in interop test. Shared JVM tests, debug assembly, and lint passed.
- Added isolated Android Keystore instrumentation tests for session persistence,
  ciphertext freshness/replacement, tamper rejection/recovery, and sign-out clearing.
  `scripts/verify-android-device.sh` selects an explicit connected device to run them;
  `:app:assembleDebugAndroidTest`, `:app:assembleDebug`, and `:app:lintDebug` passed.
- All three Keystore instrumentation tests passed on a wireless-connected RMX3261.
  The verifier now builds/installs APKs and runs instrumentation directly through
  ADB, avoiding the uncached Gradle unified-test-platform dependency. App cold launch
  and a screenshot check of the initial sign-in screen also passed.
- Earlier emulator attempts failed with socket restrictions and a pending snapshot
  error. A later loopback Rust server bind succeeded through the local shell.
  `scripts/verify-android-network.sh` passed all four instrumentation tests on RMX3261
  against a disposable PostgreSQL-backed HTTP server through ADB reverse forwarding.
  Verified real-client sends/retries, membership rejection, local cache reopen/cursor
  catch-up, monotonic ACKs, and session revocation. Server, cluster, test files, and
  forwarding were cleaned up. Full UI workflow, two-device LAN, and TLS acceptance
  remain open; foreground realtime verification subsequently passed above. The sibling
  backend's disposable PostgreSQL 18.4 verification now passed all six top-level
  tests (including all three live database tests), with clean cluster shutdown.
- Working repositories are now under `/home/shafi/StudioProjects/NexusChat/`.
  Details: `docs/AUTHENTICATED_MESSAGING_SLICE.md`. Historical sections below
  describe the earlier checkpoint. Development is committed in related slices;
  no push was made.

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
