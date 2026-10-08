# Authenticated messaging client

The Android first screen now signs in or registers with the Rust backend. The AI
assistant remains available separately through the toolbar and retains its
Demo/BYOK settings and history. Human messaging never reads a provider key.

## Implemented flow

- Register/login against `/v1/auth`, restore a saved session, verify the account,
  and clear visible account data on logout or a rejected/expired session.
- Android Keystore AES-GCM encrypts the complete session record before durable
  SharedPreferences storage. Passwords and bearer tokens are not saved in
  SavedStateHandle or SQLDelight; session diagnostic strings redact credentials.
- Authorized conversation listing and creation use `/v1/messaging/conversations`.
  A new conversation uses another registered account's UUID. The account dialog
  exposes selectable account and conversation IDs; account discovery is later work.
- Send/history/received-cursor acknowledgement use the Rust HTTP routes. Requests
  take sender identity from the bearer session, with no development identity header.
  The separate messaging HTTP client disables redirects and has bounded timeouts.
- The foreground client listens to `/v1/messaging/events` and synchronizes on READY
  or authorized change hints. It also polls every five seconds as a recovery fallback,
  with explicit refresh, local history, visible queued/failed sends, and retry controls.
  No background push or recipient delivered/read indication is claimed; outgoing
  accepted messages say Sent.
- SQLDelight v3 partitions conversation, message, outbox, and cursor rows by server
  URL and account ID. The v2-to-v3 migration adds independent messenger tables;
  assistant history remains intact.
- Queue writes precede network sends. Retries retain the original UUID/body.
  Interrupted sends recover, and history reconciles an accepted send whose response
  was lost. A send ACK does not advance the history cursor. Contiguous history is
  validated and committed atomically; gaps/conflicts cannot discard queued content.
- The existing Kotlin fixture protocol remains available. Explicit
  `RealtimeWireFormat.RUST` makes the shared realtime client serialize snake_case
  fields and parse Rust ACK/message envelopes. The Android messaging UI currently
  uses a dedicated read-only WebSocket notification feed with durable HTTP
  synchronization, rather than realtime command sends through this legacy adapter.
- The feed is lifecycle-owned: foreground starts it, background/logout cancels it,
  and reconnects use exponential delay capped at 30 seconds. Hints are conflated;
  each READY triggers catch-up using the locally committed cursor. HTTP writes and
  cache transactions retain their existing idempotency and ordering guarantees.
  An old reader's cancellation cannot mark a newer connection disconnected.
- Backend notifications are bounded, process-local hints. Current membership and
  session validity are checked before each event, with periodic session checks and
  heartbeats. Lag closes a feed for reconnect/resync; notifications never advance
  received cursors themselves. Messaging command sockets now revalidate each command.

## Local setup

Run the Rust server from the sibling `NexusChat-server-rs` repository. The default
memory backend supports the client flow but loses server state on restart. Durable
runtime messaging requires the documented PostgreSQL configuration; durable file
authentication and a stable signing secret preserve sessions.

Debug Android builds accept development HTTP and default to the emulator host
address `http://10.0.2.2:3000`. Release clients require an HTTPS base URL. Cleartext
traffic is enabled only in the debug manifest. No live server is left running by
the verification workflow. A loopback Rust TCP listener subsequently worked when
started through the local shell.

## Verification

The shared tests cover Rust request/response shapes, credential/error redaction,
transport validation, account/server cache isolation, durable outbox/cursor reopen,
history gaps/conflicts, lost responses, stable retry keys, interrupted sends,
account switching, expired/revoked sessions, and upgrades preserving assistant data.

Run actual KMM/Rust interoperability with cached dependencies and JDK 17:

```sh
JAVA_HOME=/path/to/jdk17 bash scripts/verify-rust-interop.sh
```

The script builds the Rust `http_contract_fixture` example and enables the shared
interop test through `NEXUS_RUST_FIXTURE_BIN`. The test runs real Rust Axum routes,
authentication, file fixtures, and the shared Ktor HTTP adapter through JSON-line
process I/O. It registers Alice/Bob/an outsider, sends/retries, rejects outsiders,
restarts the Rust process and client SQLite database, resumes with the same bearer
sessions, recovers ordered history/cursors, and revokes a session. It exercises the
real router and wire contract, but not TCP/TLS or PostgreSQL. Without the optional
binary environment variable, this one test is explicitly skipped.

```sh
JAVA_HOME=/path/to/jdk17 bash ./gradlew --offline --no-daemon :shared:jvmTest :app:assembleDebug :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

Android Keystore instrumentation coverage uses unique test preference files and
key aliases, leaving the application's saved session untouched. It checks encrypted
session persistence across store recreation, fresh ciphertext on repeated writes,
replacement, authenticated rejection of tampered ciphertext with sanitized errors,
recovery after clearing corruption, and durable sign-out clearing. Run on a connected
device with JDK 17:

```sh
ANDROID_SERIAL=<device-serial> JAVA_HOME=/path/to/jdk17 bash scripts/verify-android-device.sh
```

The script builds both APKs, installs them, and invokes instrumentation directly
through ADB, checking the test summary because runner failures can return shell
status zero. This avoids an uncached Gradle unified-test-platform dependency.
It tests device Keystore storage, not full visual acceptance or live-network
messaging. Instrumentation APK assembly, debug app assembly, and lint passed after
adding these tests. All three tests subsequently passed on a wireless-connected
RMX3261. App cold launch and an initial sign-in screenshot check also passed.

For a reproducible real-device HTTP test against PostgreSQL:

```sh
ANDROID_SERIAL=<device-serial> JAVA_HOME=/path/to/jdk17 bash scripts/verify-android-network.sh
```

This requires PostgreSQL tools, cached Rust/Gradle dependencies, OpenSSL, and a ready
device. Keep the phone unlocked/awake; some OEMs kill instrumentation on screen lock.
The runner wakes the screen without dismissing keyguard or changing device settings.
It refuses an existing local server or device reverse mapping on port 3000,
creates a private disposable PostgreSQL cluster and file-auth store with a fresh
signing secret, starts the real Rust server, and forwards device loopback through
ADB. The opt-in `AndroidLiveMessagingTest` uses the real OkHttp/Ktor client, Android
Keystore, and Android SQLite driver with isolated test storage. It registers two
participants and an outsider, verifies sessions/conversations, exchanges messages,
checks duplicate/conflicting retries and membership rejection, reopens local cache,
catches up by cursor, checks monotonic acknowledgements, and revokes sessions.
The expanded test also opens real recipient/outsider WebSocket feeds, waits for
creation/send hints, checks outsider isolation, reconnects for missed-message
catch-up, and verifies an already-open feed closes after session revocation.
All four instrumentation tests passed on RMX3261 against this PostgreSQL-backed
HTTP server. The runner removed forwarding, stopped the backend/database, and
deleted test state. This is one physical device with multiple account sessions,
not two devices, LAN routing, TLS, or full UI workflow testing. The expanded realtime
test also passed all four device tests against PostgreSQL. An initial attempt was
killed by the device's lock-screen cleanup before any test ran; retrying with the
phone unlocked succeeded.

The local interop test, shared JVM suite, debug assembly, and Android lint passed.
The earlier read-only emulator attempt failed with sandbox socket restrictions
and a pending snapshot error. Full UI workflow, two-device LAN routing, and TLS
verification remain open despite the successful device Keystore and forwarded
HTTP/PostgreSQL checks.
Live PostgreSQL verification subsequently passed after installing PostgreSQL 18.4:
the sibling backend's disposable runner passed all six top-level tests, including
all three live database tests, over a private Unix socket and cleaned up its cluster.
This does not extend the KMM process-bridge test to PostgreSQL or certify TCP/TLS.

## Remaining scope

Contact discovery/requests, profiles, recipient receipts, bounded/paginated history,
background synchronization/push, media, WebRTC calling, E2EE, and deployment remain
separate unfinished milestones. Local messages are plaintext SQLite data; securing
the session token does not encrypt message history.
