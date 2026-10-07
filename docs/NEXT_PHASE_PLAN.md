# NexusChat: dependency-ordered messenger implementation roadmap

## 1. Reviewed baseline and scope

Reviewed repository HEAD `b0c5614` after the incremental messenger/domain, server, durable AI lifecycle and Android navigation commits (`7f8b05d`, `c994e24`, `a41551c`, `92163c4`). The working tree was clean at review start. This review edits this document only; it does not implement the roadmap or change source, dependencies, CI or configuration. Concurrent source/document edits appeared later; they are identified separately below and were not made or reverted by this review.

Product target: Android-first accounts, contacts, reliable 1:1/group messaging, durable WebSocket synchronization, push, media and audio/video calling. Security is a dependency throughout, not a final coat of paint. Shared JVM tests are not a desktop application; iOS, desktop, linked devices and group calls are later milestones. BYOK remains a separate, optional assistant capability. Human registration, sending and calling must work with no LLM key and during provider outages.

### Committed baseline at review start

| Evidence in repository | Implemented | Not implemented / consequence |
|---|---|---|
| `domain/messenger/MessengerModels.kt`, `MessengerPorts.kt`, `MessengerState.kt`, `MessengerDomainTest` | Serializable domain objects, repository/gateway interfaces, delivery transitions and ordering helpers | No concrete messenger repository, client WebSocket adapter, durable messenger outbox, key protocol or connected messenger UI. A serialized outbox operation is not persisted delivery. |
| `server/Server.kt`, `transport/Envelope.kt`, `ServerTest` | Ktor `/healthz`, `/v1/realtime`, typed send/ack and presence/typing/call-signal frames; local WebSocket ack test | Send only acknowledges the sender; there is no recipient message event/fan-out, history, resume, receipt endpoint or membership enforcement. |
| `server/auth/SessionAuthenticator.kt` | Constrained `X-Dev-User-Id` development header | Anyone can choose an identity. No accounts, verified credentials, device binding, expiry, refresh or revoke. Never expose this server publicly. |
| `server/domain/MessageRepository.kt` | In-memory user/idempotency-key deduplication | Message content is not durably stored; state disappears on restart. Sequence is global, not per conversation; separate `incrementAndGet()` / `get()` reads can disagree under concurrency. Reusing a key with different content is not rejected. An ack does not satisfy the domain's durable `SENT` contract. |
| `Server.kt` broadcast helper | Presence, typing and call frames forwarded to connected sessions | Broadcast goes to all users rather than authorized conversation members; call/presence payloads lack policy validation, TTL and recipient authorization. Disable these paths outside isolated fixtures until scoped routing exists. |
| `AppContainer.kt`, `MainActivity.kt`, `ChatViewModel.kt`, `Chat.sq` | Application-scoped HTTP/SQLite dependencies, lifecycle-owned ViewModel, saved-state adapter; persisted AI sessions/turns and interruption handling | `realRepository` is `OfflineFirstChatRepository(KtorLlmStreamingClient, ...)`: “real” means BYOK AI, not human messaging. SQL contains only `chat_session` / `chat_message`, not accounts/members/outbox/cursors. |
| `ui/NexusApp.kt` | Inbox/chat/settings/profile routes and explicit demo mode | Inbox rows are AI sessions. Profile, communities/calls, menu actions and updates/channel content are placeholders or static fixtures. Routes use `remember`; full navigation/process restoration needs device tests. |
| Manifest, Gradle, CI | Internet permission, backup disabled, Android/JVM build, JDK 17 CI, backend test task | No FCM, WorkManager, media/call platform adapters, WebRTC integration or instrumented test sources. App unit test task currently has `NO-SOURCE`. CI checks populated shared tests, but not populated server/app/device suites; server reports are not included in the upload paths. |

Protocol integration must reconcile server uppercase `SEND_MESSAGE`/`MESSAGE_ACK` frames with shared lowercase outbox serial names and richer user/device/message models. They are separate contracts today, not wire-compatible merely because both use kotlinx.serialization. Freeze version/error/receipt/cursor semantics before writing an adapter.

### Concurrent working-tree changes observed at final inspection

Other work modified `CHECKPOINT.md`, `docs/MESSENGER_DOMAIN_ADR.md`, `Server.kt`, `MessageRepository.kt`, `Envelope.kt` and `ServerTest.kt` during this review. The last inspected source now adds:

- Scripted conversation membership checks, synchronized per-conversation sequence allocation and after-sequence acknowledgement lookup in `InMemoryMessageRepository`; this addresses the baseline allocator issue but is still not real identity/membership management.
- A `FileMessageRepository` fixture that persists owner + acknowledgement metadata through temporary-file rename. It does not retain message text, perform database transactions, fsync for crash durability, coordinate multiple processes or persist fan-out jobs. Default application wiring still uses the in-memory repository. Generated `msg-$seq` IDs repeat across conversations unless scoped; idempotency payload conflicts remain unresolved.
- Version fields/default `PROTOCOL_VERSION = 1`, typed error/history frames and WebSocket handling for history and version rejection. History returns acknowledgement metadata, not recoverable message content; receipt/fan-out/client adapters remain absent. Defaulted version fields do not by themselves prove strict negotiated compatibility.
- A membership/cursor regression test alongside the existing server tests. These changing files were not certified by the earlier baseline regression run. In-progress ADR/checkpoint wording may lag source; source inspection, not those claims, determines this roadmap.

These are useful incremental fixtures, not completion of slices 1 or 2. Preserve them when implementing the roadmap; extend/replace the fixture storage with real durable message records and transport contracts rather than redoing already-added shapes. Global presence/typing/call broadcast and development identity remain unsafe for public use. Re-run the common regression after concurrent work settles; do not attribute its changes or claimed test outcomes to this documentation review.

### Verification performed during this review

Before the concurrent edits were observed, the initial parallel full regression run failed because the Gradle daemon disappeared; no cause is established. The same task set succeeded with a single worker, no parallel execution and a 2 GiB single-use daemon (command below). Populated shared JVM XML reports have zero failures/errors/skips; the inspected server report contains its three baseline tests with zero failures/errors/skips. SQLDelight migration verification, Android lint and debug assembly completed. Lint still reports warnings; success is not “warning-free”. `:app:testDebugUnitTest` was `NO-SOURCE`, not Android behavioral coverage. These results do not certify the subsequently observed working-tree source changes. No live provider, staging, push, media, E2EE or device/call verification was performed.

`adb`, `docker` and `psql` were not found on this session's PATH. That is a local provisioning dependency, not evidence that backend work requires cloud credentials. File-backed JVM/SQLite fixtures and the existing Ktor test host can be used now; provision a database/container runtime and Android platform tools when their gates require them.

## 2. Ordered execution and ownership

Execute one acceptance-tested slice at a time; do not add disconnected contacts/call screens and call them messaging. Each implementation PR records client owner, backend owner, security reviewer, migrations, test XML/logs, device/environment evidence and unresolved blockers. Owners are unassigned until a named maintainer accepts them; this plan does not invent staffing or credentials.

| Order | Deliverable and dependency | Can implement locally now | External gate / stop condition |
|---|---|---|---|
| 0 | Identity, protocol and security ADRs; prerequisite for slice 1 | Choose login/recovery policy, versioned API/WS DTOs, limits, device ownership, receipt meaning, retention and data flows. Evaluate maintained Signal library licensing/platform/group support in parallel. | Product/security approval before public use; no invented custom crypto or unreviewed E2EE claim. |
| 1 | **Slice 1: account + manual contact + authorized direct conversation** | Durable local backend identity/membership store, real credential validation, session/device APIs, Android secure session adapter and onboarding/contact UI. | Public identity/recovery provider if chosen; TLS staging, secrets and two clients for deployment evidence. |
| 2 | **Slice 2: durable 1:1 text + reconnect/resume**; requires slice 1 identities/membership and frozen protocol | Backend transaction store/outbox, authorized WS fan-out/history; SQLDelight messenger storage, client adapter and real timeline. | Two Android installations, restart/network testing; plaintext local fixtures cannot ship or pass private-messenger acceptance. |
| 3 | **Slice 3: reviewed E2EE 1:1 on the same durable path**; requires slice 2 and library decision | Key directory, device key/prekey lifecycle, reviewed-library adapter, trust UX, ciphertext transport and integration tests. | Licensing/compatibility resolution, independent review and real-device interoperability. Block if the selected library does not meet requirements. |
| 4 | Groups, member roles and encrypted group delivery; requires 1-3 plus reviewed group support | Group API/UI, membership revisions, invitation policy, role checks, receipt aggregation, rekey tests. | Multi-user staging and security review; no plaintext groups as an E2EE fallback. |
| 5 | Android push/background catch-up; requires durable per-device sync and privacy controls | Notification UX, scheduler, token interfaces and fake-push tests; backend transactional push jobs. | Firebase project/config, service credentials, real FCM-capable devices and background delivery evidence. |
| 6 | Encrypted media; requires 3, authorized conversations and durable jobs; may run alongside 5 | Picker/preview, encrypted descriptors, durable upload state, local object-store adapter and fault tests. | Private object storage/CDN, scoped grants, quotas/cleanup and staging/device evidence. |
| 7 | 1:1 audio, then video; requires 1-3, scoped signaling, push and call notification policy | Call state/history, WebRTC adapter, permission/audio routing and local signaling/TURN experiments. | Operated STUN/TURN, short-lived relay credentials, separate-network physical devices, background ringing and security review. |
| 8 | Production hardening and release; requires all advertised features' gates | CI coverage gates, bounded parsing, redaction, accessibility, migrations, release/rollback runbooks. Abuse protections start in slice 1. | Security/abuse review, signed release custody, deployment/monitoring, restore drills, approved SLO/cost budgets. |
| 9 | Linked devices, iOS/desktop, group calls; after Android baseline | Portable capability contracts and compatibility fixtures only. | Actual clients/toolchains/signing, enrollment/history transfer protocol, APNs, SFU operation/media-E2EE decision. |

Backend implementation is work to do, not a reason to wait for cloud access. Use a modular Ktor backend first. A local durable database can prove commit/restart behavior; it does not prove production deployment, backups, scale or public TLS. Select one production database in order 0 and run integration tests against that engine before staging, even if small unit fixtures use SQLite. Redis/microservices are not prerequisites for a single-node slice.

## 3. Common verification contract

All commands run from `/home/shafi/StudioProjects/NexusChat-KMM`. This setup and baseline command were exercised successfully in the bounded configuration:

```bash
export JAVA_HOME=/home/shafi/.jdks/jbr-17.0.14
export PATH="$JAVA_HOME/bin:$PATH"
g() {
  bash ./gradlew "$@" --no-daemon --max-workers=1 --no-parallel \
    -Dorg.gradle.jvmargs='-Xmx2048m -Dfile.encoding=UTF-8' --console=plain
}
g :server:test :shared:jvmTest :shared:verifySqlDelightMigration \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --rerun-tasks
```

Use this report check after each named suite below. It fails on missing, empty, failed or skipped suites instead of treating `NO-SOURCE`/old unrelated reports as evidence. `--rerun-tasks` reruns the selected suites; each slice requires its own exact report names. Python 3 is a prerequisite for this check.

```bash
verify_reports() {
  python3 - "$@" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
for name in sys.argv[1:]:
    path = Path(name)
    assert path.is_file(), f"Missing report: {path}"
    root = ET.parse(path).getroot()
    cases = root.findall(".//testcase")
    assert cases, f"No tests executed: {path}"
    assert not root.findall(".//failure"), f"Failures: {path}"
    assert not root.findall(".//error"), f"Errors: {path}"
    assert not root.findall(".//skipped"), f"Skipped tests: {path}"
    print(f"PASS {path}: {len(cases)} executed tests")
PY
}
```

The suite names in slices 1-3 are **required future deliverables**, not existing tests or current passing results. Keep these names or update this plan in the implementing PR. Gradle's test filtering must fail on no matching tests; do not disable that behavior. Device commands likewise require instrumented tests and dependencies to be added. Provision `adb`, a supported emulator/device and test runner first. JVM doubles cannot close device gates.

## 4. Next three vertical slices

### Slice 1 — Real local accounts, manual contacts and authorized direct conversation

**Outcome:** Alice and Bob can create separate accounts, log in, add each other by handle and open a shared direct conversation in Android, independent of BYOK. An unrelated account cannot access it. Messaging itself remains disabled until slice 2.

**Implement in this order:**

1. Approve identity ADR. Recommended initial scope: username + password via a maintained password-hashing/authentication library, no mandatory phone discovery; explicitly unverified handles. Select recovery/verification policy before public registration. Do not write password crypto, embed credentials or fabricate verified phone/email status. Email/phone/passkey alternatives require a corresponding ADR and provider setup.
2. Add durable backend users/devices/session/contact/conversation/member schema with migrations and uniqueness constraints. Define versioned errors, paged handle lookup, enumeration/spam limits, block semantics and idempotent direct-conversation creation. Public IDs are not phone numbers.
3. Add registration/login, rotated refresh, logout/revoke and authenticated contact/direct-conversation APIs. Hash refresh secrets in the backend; store client credentials with Android Keystore-backed storage. Bind sessions to device ownership; reject forged dev headers on non-development paths. Fail closed when production auth configuration is missing.
4. Add shared `AuthRepository` and concrete contacts implementation, account-scoped SQLDelight messenger tables and an Android composition-root branch for Messenger. Preserve DEMO/BYOK as clearly separate assistant modes; do not reinterpret old AI history as human messages. Define signed-out/account-switch database isolation and revoked-session cleanup.
5. Wire onboarding, profile, manual contact add/search and direct conversation entry end-to-end to the local backend. Add Android behavior tests rather than testing only repository doubles. Contact permission is not needed for manual entry; address-book discovery is deferred.

**Acceptance criteria:**

- File-backed reopen tests preserve users/contacts/membership and legacy AI sessions; account switch never exposes another account's contacts/history. Direct creation is idempotent and constrained to two valid participants.
- Wrong passwords, expired/replayed refresh, revoked/foreign devices, forged headers and nonmember history/send/signaling attempts are rejected. Logout cancels networking and prevents queued work from running under a different account.
- Handle search is bounded/rate-limited; blocking denies new requests according to the ADR; logs contain no passwords/tokens/PII payloads. No plaintext credential persistence.
- Android registration/login/contact/open-conversation flow works with empty LLM keys and after app relaunch; no fake profile/sample channels appear as real account data. Device tests prove secure session recovery and logout isolation.

**Exact verification commands, after implementation:**

```bash
g :server:test --tests 'com.example.nexuschat.server.AccountContactSliceTest' --rerun-tasks
g :shared:jvmTest --tests 'com.example.nexuschat.domain.messenger.AccountContactSliceTest' --rerun-tasks
g :shared:verifySqlDelightMigration :app:lintDebug :app:assembleDebug --rerun-tasks
verify_reports \
  server/build/test-results/test/TEST-com.example.nexuschat.server.AccountContactSliceTest.xml \
  shared/build/test-results/jvmTest/TEST-com.example.nexuschat.domain.messenger.AccountContactSliceTest.xml
adb devices -l
g :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=shafi.example.nexuschat.AccountContactSliceInstrumentedTest \
  --rerun-tasks
```

Implement `AccountContactSliceInstrumentedTest` with a local backend fixture and a seeded peer account, endpoint injection and fresh isolated account state. Preserve connected-test XML under `app/build/outputs/androidTest-results/connected/`; require the named class to execute with no failures/skips (device-specific report filenames vary). No production credentials are needed for this local slice. Public verified identity/recovery, TLS staging and a second installation remain separate external gates.

### Slice 2 — Durable 1:1 delivery, recipient receipts and restart-safe synchronization

**Outcome:** Alice sends real human text to Bob; an offline send survives client process death, and accepted messages survive server restart and reach Bob once. This is an isolated development slice until E2EE in slice 3; no private-message release claim.

**Implement in this order:**

1. Version shared/server wire DTOs with explicit adapter/contract tests. Add message receive, device receipts, resume/history, sequence/cursor, auth expiry, payload limits and typed error events. Resolve `SENT -> FAILED` handling and delayed acknowledgements in `MessengerState.kt`: an ambiguous transport timeout must not erase durable server acceptance or allow receipt regression; test read-before-delivered events and retries.
2. Replace `InMemoryMessageRepository` with a transactional persistent store: per-conversation sequence allocation, stable sender/device/client IDs, idempotency uniqueness plus payload-conflict rejection, encrypted-envelope-ready payload format and durable fan-out job outbox. Acknowledge only after commit. Test concurrent allocators, commit failure and restart using file-backed/real-engine fixtures, not just a fresh in-memory object.
3. Authorize every send/history/receipt against account/device/current membership. Deliver only to recipient devices; remove global broadcasts from production paths. Persist fan-out work and recipient receipts; delivered means a recipient durably stored the message, read means explicit user action under privacy policy, not socket write or notification arrival.
4. Add SQLDelight conversations/messages/durable operation outbox/receipts/sync cursors/drafts, separately from AI tables. Message + outbox enqueue and incoming message + cursor update must each be atomic. Stable IDs survive retries; acknowledgements reconcile rather than create duplicate visible rows.
5. Implement `RealtimeTransport` using Ktor client WebSockets (dependency is not present in shared today), authenticated session lifecycle, heartbeat deadlines, capped exponential backoff/jitter, bounded queues, resume and paginated gap repair. Handle cursor expiry/full resync and tombstones. Stop on logout/revoke; foreground socket plus eventual scheduled/push catch-up, not an always-running background socket promise.
6. Wire real messenger repository/timeline into Android: queued/sending/server-accepted/delivered/read/failed states, retry/cancel and connection state. Recovery drains durable work after restart without resending paid LLM requests.

**Acceptance criteria:**

- Two independent clients exchange text without an LLM key. Unauthorized third account cannot receive messages/presence/typing or forge receipts. Server acceptance is backed by a committed message/job visible after restart.
- Offline enqueue + hard client relaunch, dropped ack after commit, duplicate send/receipt, reordered events, server restart, concurrent sends, network handoff and token expiry produce no lost or duplicate visible messages.
- Cursor and incoming row commit atomically; disconnect between receipt and commit cannot skip messages. Expired cursors repair history with bounded paging; blocked/revoked users cannot regain access by reconnecting.
- Pending/sending states remain distinct from server acceptance and recipient receipts. Retry retains stable IDs; conflicting reuse is rejected. Background socket loss never implies message loss.

**Exact verification commands, after implementation:**

```bash
g :server:test --tests 'com.example.nexuschat.server.DurableMessagingSliceTest' --rerun-tasks
g :shared:jvmTest --tests 'com.example.nexuschat.domain.messenger.DurableMessagingSliceTest' --rerun-tasks
g :shared:verifySqlDelightMigration :app:lintDebug :app:assembleDebug --rerun-tasks
verify_reports \
  server/build/test-results/test/TEST-com.example.nexuschat.server.DurableMessagingSliceTest.xml \
  shared/build/test-results/jvmTest/TEST-com.example.nexuschat.domain.messenger.DurableMessagingSliceTest.xml
g :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=shafi.example.nexuschat.DurableMessagingSliceInstrumentedTest \
  --rerun-tasks
```

The server suite must stop/reopen its durable store and reinstantiate the application with pending jobs; the shared suite must reopen a file-backed client database with faults at transaction boundaries. Device suite exercises the real adapter against a local server/peer fixture. In addition, on two provisioned Android installations: enqueue offline, kill/relaunch, reconnect, restart the backend, and compare both timelines/receipt states. Capture logs/test evidence for each case; a single test-host ack is insufficient.

For process interruption on a provisioned Android device (run separately from instrumentation, which force-stop would kill):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n shafi.example.nexuschat/.MainActivity
# After enqueueing offline in the UI, interrupt and reopen without clearing data:
adb shell am force-stop shafi.example.nexuschat
adb shell am start -n shafi.example.nexuschat/.MainActivity
```

This deliberately tests a harsh stop; normal OS process death and reconnect require their own test evidence. Local `ws://` access needs a debug-only endpoint/network-security policy; do not enable production cleartext globally. Physical-device routing, TLS/WSS staging and production-engine restart evidence require provisioned backend/network/devices, not FCM credentials yet.

### Slice 3 — Encrypted 1:1 delivery, key trust and device revocation

**Outcome:** The same offline/reconnect conversation delivers reviewed-protocol ciphertext; the relay cannot read content, users see identity changes and revoked devices cannot fetch new traffic. This is the first private-text candidate, not automatic certification of the whole product.

**Implement in this order:**

1. Close the Signal implementation ADR: exact maintained library/version, license, Android/KMP boundary, persistence needs, test vectors/interoperability, supported group approach and upgrade policy. Do not assume a JVM library works in common Kotlin or future iOS. If unsuitable, stop and evaluate another reviewed implementation, not custom handshakes.
2. Implement authenticated device-key/signed-prekey/one-time-prekey directory, replenishment and atomic consumption. Bind publication/fetch/revoke to identity/device ownership. Define directory substitution threat and safety-number verification guarantees; document remaining metadata and unverified-identity risk.
3. Add platform-secured identity/session secrets, reviewed protocol session establishment and ciphertext outbox. Make protocol-session mutation + outgoing ciphertext persistence crash-consistent; incoming decrypt/session/message/cursor commits must prevent ratchet rollback or duplicate UI rows. The backend accepts only versioned ciphertext envelopes in the encrypted mode and does not log plaintext.
4. Add trust/key-change and safety-number UX, blocked/revoked-device behavior, replay/old-key handling and offline prekey exhaustion states. Recovery must not silently restore a trusted identity from insecure backup. Multi-device enrollment/history transfer remains disabled until designed.
5. Decide local database encryption and notification previews separately from Keystore/transport; enforce backup exclusions and redaction. Keep AI history separated. Explicit AI context export requires consent and leaves the conversation E2EE boundary; disable automatic history export.

**Acceptance criteria:**

- Known protocol vectors plus two independent client sessions prove offline first-message setup, prekey depletion/replenishment, duplicate/out-of-order decrypt, restart mid-send/receive and identity change/revoke behavior.
- Backend stored payloads/jobs/logs contain no message plaintext or content keys. An unauthorized device cannot publish another user's keys, fetch their messages or bypass revoke. Replay cannot create duplicate messages or regress cryptographic state.
- Two physical devices exchange encrypted text over WSS with offline/restart recovery; trust warning and verification UX are exercised. Stolen session and malicious directory scenarios are documented/tested under the threat model, not dismissed because TLS exists.
- External security reviewer signs off on integration before any production E2EE claim. Plaintext local fixtures, a round-trip using a single mock crypto adapter, or library adoption alone cannot close this gate.

**Exact verification commands, after implementation:**

```bash
g :server:test --tests 'com.example.nexuschat.server.EncryptedMessagingSliceTest' --rerun-tasks
g :shared:jvmTest --tests 'com.example.nexuschat.domain.messenger.EncryptedMessagingSliceTest' --rerun-tasks
g :shared:verifySqlDelightMigration :app:lintDebug :app:assembleDebug --rerun-tasks
verify_reports \
  server/build/test-results/test/TEST-com.example.nexuschat.server.EncryptedMessagingSliceTest.xml \
  shared/build/test-results/jvmTest/TEST-com.example.nexuschat.domain.messenger.EncryptedMessagingSliceTest.xml
g :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=shafi.example.nexuschat.EncryptedMessagingSliceInstrumentedTest \
  --rerun-tasks
```

The shared suite tests protocol orchestration and persistent transaction behavior with the selected library where target-compatible; Android instrumentation must exercise the actual platform crypto/secure-storage adapter, not substitute a mock. Preserve protocol-vector provenance, device logs, server-storage inspection and review findings. Tests can be developed locally without cloud credentials; maintained-library approval, WSS staging, physical devices and independent review remain release blockers.

## 5. Subsequent executable milestones

Implement the following only on the durable, authenticated, encrypted foundation. Each milestone adds populated client/backend/device tests to the common regression command, records precise commands in its PR and cannot be closed solely by mock UI tests.

### Groups, contacts/discovery and ephemeral state (order 4)

- Add group create/invite/join/leave/remove APIs and UI, authoritative owner/admin roles, member limits and revision/conflict handling. Commit membership changes and recipient/key distribution consistently; enforce authorization again when delayed jobs are dispatched.
- Use the reviewed library's supported group mechanism. Rotate distribution/session state on membership/device changes; removed members get no new envelopes/keys. Specify what history new members may see and what ex-members retain; do not promise remote deletion erases copies already received.
- Add per-recipient delivery/read aggregation and privacy controls. Typing/presence are authorized, throttled, TTL-expiring hints with hidden/stale states, never durable delivery indicators. Block relationships apply to discovery, presence, invitations and signaling.
- Keep manual handles/invites working without address-book permission. Add contextual Android contact permission only with explicit discovery consent, normalization policy and server anti-enumeration controls. Uploading an entire address book by default or plain hashes of enumerable phone numbers is not privacy-preserving discovery.
- Gate: concurrent role/member edits, offline members, stale revisions, remove during fan-out, rekey, receipt privacy, permission denial/revocation and outsider access pass on multi-user staging. Phone/email discovery requires a provider/privacy decision; secure group-library support is mandatory.

### Push/background synchronization (order 5)

- Implement device-bound FCM registration/token rotation/unregister, transactional server push jobs, retry/backoff and invalid-token removal. Push contains minimal opaque wakeup/routing hints, not plaintext or content keys.
- Add notification channels/permission UX, mute/preview privacy, deduplication and deep links. WorkManager/background catch-up uses durable cursors; push is a hint, never the authoritative store. Account switch/revoke invalidates jobs and notification access.
- Gate: physical FCM-capable devices cover foreground/background, OS process death, denied permission, rotation, dropped/duplicate push, battery restrictions and offline catch-up. Document Android force-stop limitations; do not promise push delivery after a user force-stop. Requires Firebase app configuration, backend credentials in secret storage and real devices. Fake push only closes local scheduling tests.

### Encrypted attachments and media (order 6)

- Use platform picker/scoped camera access, preview, size/type limits and bounded compression; avoid broad storage permission. Encrypt with reviewed integration; content key/integrity metadata travels only in E2EE messages, never public object URLs. Protect thumbnails too.
- Implement scoped short-lived upload/download grants, resumable/idempotent upload, durable recovery, cancellation, grant renewal and integrity validation. Publish the message only after successful upload; expire/orphan-clean objects and honor retention/deletion/quotas.
- Gate: interrupted upload/process restart, duplicate completion, corrupted ciphertext, expired grants, outsider download, orphan cleanup and constrained-memory/device tests pass. Local object-store adapter can be implemented now; real storage/CDN credentials, grants and cleanup operations are backend dependencies. Server-side plaintext malware scanning conflicts with E2EE; document endpoint-side validation/abuse policy rather than quietly decrypting attachments on the server.

### 1:1 audio, then video and call history (order 7)

- First replace global call broadcasts with conversation/device-authorized targeted signaling. Version invite/accept/reject/cancel/SDP/ICE/hangup events, stable call IDs, revisions, TTLs and rate limits. Handle busy/simultaneous calls, early cancel, duplicate/out-of-order ICE, timeout and reconnect. Persist/reconcile call history, terminal reason and missed-call deduplication.
- Add maintained WebRTC platform adapter, microphone/camera permission denial, foreground/background incoming-call policy, notification/telecom or foreground-service integration as applicable, speaker/Bluetooth routing and deterministic cleanup. Start with audio; add video only after audio acceptance.
- Local peers/local TURN prove integration only. Operate STUN/TURN with short-lived scoped credentials, relay allocation limits, egress budget and monitoring. STUN alone is not sufficient for restrictive networks. Call invite push depends on order 5.
- Gate: two physical devices on different Wi-Fi/mobile networks pass forced TURN relay, network handoff, background ringing, busy/reject/missed, revoked/outsider signaling, permission denial and hangup cleanup; no mic/camera remains active afterward. Measure setup success/latency/relay use and history agreement. WebRTC transport encryption is not automatically authenticated product E2EE. Review signaling/participant authentication and media privacy separately.
- Group calls require an SFU/capacity design and explicit SFU media-E2EE support/review; do not promise unlimited mesh or inherited 1:1 privacy guarantees.

### Security/operations and Android release (order 8)

- Server-side limits start in slice 1: registration/login/recovery, lookup, invites, messages/frames, uploads and TURN allocation. Add contact-request controls, block/report/mute, bounded parsing/queues, redaction, consent-based reporting of selected evidence, privacy/retention/account export/deletion and recovery warnings. Client-only throttling is not abuse prevention.
- Maintain threat model for compromised relay/key directory, token replay, device theft, endpoints, backups, metadata and AI exports. TLS is mandatory and separate from E2EE; Keystore is separate from database encryption. A malicious recipient can retain screenshots/content; deletion cannot erase all copies.
- Add named populated app unit/instrumented gates, server XML assertions/report uploads and migration verification to CI. Require accessibility, supported API/device matrix, upgrade/relaunch and battery/data/low-network soak evidence. Review dependency advisories without opportunistic unrelated toolchain changes.
- External release inputs: DNS/TLS and staging/production deployment ownership, secrets rotation, sanitized metrics/alerts, durable jobs, backups/restore drills, incident response, moderation process, approved SLO/load/cost ceilings, external security review, signing custody and store privacy declarations. Never embed credentials in Gradle/source or this document.
- Release only features backed by staging/device evidence. Explicitly defer group calls/linked devices/future platforms if not implemented. Keep assistant disabled until context consent/group policy/provider disclosure is complete; never synchronize BYOK secrets with contacts or replay paid requests through the messenger outbox.

## 6. Completion ledger and documentation verification

For each slice/milestone, append a ledger entry with: assigned owner(s), client/server commit IDs, migration versions, API/protocol/library versions, environment, exact commands and report paths, device/network matrix, security approval and open blockers. “Compiles”, “mock passes”, “task NO-SOURCE” and “screen exists” are not implementation completion.

Current ledger: reviewed HEAD `b0c5614`; baseline regression completed in the bounded configuration above; messenger slices 1-3 and every external acceptance gate remain open. No credential provisioning or device availability is assumed.

Documentation-only verification from repository root:

```bash
git diff --check
git diff --stat
git diff -- docs/NEXT_PHASE_PLAN.md
git status --short
```

Only `docs/NEXT_PHASE_PLAN.md` was modified by this review. Other concurrent changes listed above must remain untouched and must not be attributed to it. Do not stage, commit, revert or edit source/build files as part of this documentation task.
