# NexusChat: real-time messenger product and architecture plan

## 1. Top-level product direction

NexusChat is being re-scoped from an Android local-first AI BYOK beta into an Android-first real-time messenger, comparable in product scope to WhatsApp, Viber and Telegram. The core product is communication between people: accounts, contacts, private 1:1 and group conversations, reliable offline messaging, media, push notifications, audio/video calls, call history and understandable delivery/privacy states. Comparable scope is a roadmap, not a claim of current feature parity, scale, interoperability or production readiness.

The current `KtorLlmStreamingClient` / BYOK feature becomes an **optional AI assistant inside conversations**, not the core transport. Provider HTTP/SSE streams must never stand in for person-to-person message delivery, authentication, WebSocket synchronization or call signaling. A provider key must not be required to register, message or call another person.

Android is the first shipping client. Keep portable domain models, persistence contracts, protocol state machines and testable presentation in KMP shared code; keep permissions, lifecycle, secure storage, push and WebRTC integration behind platform adapters. Future iOS and packaged desktop clients follow the proven backend/security protocol rather than defining the first release's scope. JVM tests are not a shipped desktop client.

This document supersedes the previous BYOK-beta-only release direction and its PR ordering. It is a proposed architecture and delivery plan, not evidence that messenger services exist. The working tree already contains ongoing AI/local-persistence changes; this planning change does not modify source code or build files, certify those changes, or repeat old build results as current verification. Existing AI history must be preserved or explicitly migrated into assistant conversations, never relabeled as delivered human messages.

## 2. Architecture and system boundaries

### Client responsibilities

- Application-owned composition root and lifecycle-owned presentation; typed routes for authentication, inbox, contacts, conversation, group details, calls, settings and linked-device management when available.
- Local SQLDelight source of truth: accounts/devices, conversations, members, messages, attachments, durable outbox, acknowledgement state, synchronization cursors, tombstones, drafts and call history. Schema migrations preserve existing AI sessions and distinguish assistant content from human content.
- Separate `AuthRepository`, `ContactsRepository`, `MessagingRepository`, `MediaRepository`, `CallRepository`, `PresenceRepository` and optional `AiAssistantRepository`; do not stretch the LLM repository into a messenger protocol.
- HTTPS control API for account/device management, history bootstrap, group operations and upload authorization; authenticated WebSocket event channel for message delivery, acknowledgements, synchronization, presence and call signaling. Version the schemas and error contract before connecting clients.
- Platform adapters for Android Keystore, contact permissions, WorkManager, FCM, notifications, microphone/camera/audio routing and WebRTC. Inject clock, IDs, dispatcher, network reachability and transport doubles for deterministic tests.
- A reviewed Signal Protocol implementation handles message encryption and key/session lifecycle. Platform integration, licensing, maintenance, group support and target compatibility must be evaluated before choosing a library; do not assume an Android implementation is directly callable from common Kotlin.

### Server/backend responsibilities

Start with a modular backend and clearly owned contracts; independent microservices are not a prerequisite. Required boundaries are:

| Boundary | Responsibilities and infrastructure |
|---|---|
| Identity/authentication | Registration and verification, account recovery, session/token issuance and rotation, device registration/revocation, account deletion; relational identity store and verification-provider integration. |
| Contacts/discovery | Usernames/invite links and consent-based phone discovery, enumeration defenses, block relationships; phone numbers are optional discovery identifiers subject to an identity ADR, not public database keys. |
| Messaging/groups/sync | Conversation membership and authorization, durable encrypted-envelope storage, per-device fan-out, ordering/cursors, idempotency, acknowledgements, offline delivery and retention. |
| WebSocket gateway | Authenticated connections, session expiry, heartbeat, backpressure, reconnect/resume and routing; shared ephemeral connection registry when scaled. |
| Key directory | Reviewed protocol's identity/device keys and prekeys, replenishment, device-list change handling and key-change notifications; no server-held plaintext message keys. |
| Media | Authorized upload/download grants, encrypted object storage, quotas, expiry and orphan cleanup; CDN/access policy and deletion lifecycle. |
| Push | FCM token registry, per-device wakeup dispatch, retries and invalid-token removal; future APNs adapter. Push is a hint to synchronize, not the authoritative message store. |
| Calls | Membership-authorized signaling, device routing, call lifecycle/timeouts and short-lived TURN credentials; operated STUN/TURN plus an SFU decision for group calls. |
| Trust/operations | Abuse reports, rate limits, moderation workflow, sanitized metrics/audit events, backups, incident response, capacity/cost budgets and deployment/rollback ownership. |

Use a durable relational database for authoritative account/membership/message-envelope state, object storage for encrypted attachments, and an ephemeral store for presence/rate counters/connection routing where needed. Add a durable job queue or transactional event outbox for fan-out/push work so a server crash cannot commit a message but lose its notification job. TLS, secret management, DNS/certificates, staging and production environments, monitoring, restore drills and operations ownership are release dependencies, not client features.

## 3. Privacy and E2EE security decision gate

Define and review the threat model before claiming private or end-to-end encrypted messaging. Use a maintained, reviewed **Signal Protocol implementation rather than custom crypto**. Document the exact library/version, licensing, supported platforms, update policy and independent security review; library adoption alone does not audit the product integration.

### Threat model and limits

- Protect message/attachment content against network interception and a compromised or curious relay/storage server. TLS remains mandatory but is not E2EE. Identity impersonation, malicious key-directory substitution, stolen tokens, compromised endpoints and unauthorized device linking require explicit mitigations.
- Integrate identity keys, signed/one-time prekeys, asynchronous session establishment, replay protection, forward secrecy and post-compromise recovery through the reviewed implementation. Specify supported group encryption/key distribution and membership-change rotation; if secure group support is unresolved, group release is blocked, not silently plaintext.
- Provide safety-number/key verification UX and visible identity/device-key change warnings. Review directory authenticity/key-transparency options and the exact guarantees when users do not verify identities. New devices require authorization; revoke devices and rotate affected sessions/group keys. Recovery must not silently reset trusted identity or restore secrets from an insecure backup.
- Encrypt media on the sender device with reviewed primitives/integration; send its content key and integrity metadata only inside the E2EE message. Store ciphertext, not plaintext thumbnails, on the backend. Never invent a cipher, handshake or group key scheme.
- Keystore-protected protocol secrets do not automatically encrypt SQLite. Decide encrypted local storage, backup exclusion/encrypted backup, notification previews, retention, deletion and device-loss recovery separately. Protect tokens and keys; redact content, contact data and secrets from logs/crash reports.
- E2EE does not hide all metadata: accounts, device identifiers, membership, IP/timing, ciphertext sizes, routing and some call metadata remain visible. Define retention/minimization for each and optional privacy controls for presence/read receipts.
- A compromised recipient device, screenshots, forwarded content or a recipient retaining a message defeats remote-deletion promises. Do not claim deletion erases all copies. Report/block flows may intentionally share selected decrypted evidence only with explicit user consent.
- WebRTC's transport encryption is not automatically a product-wide E2EE guarantee. Review participant authentication, signaling integrity, relay/SFU visibility and media E2EE separately, especially for group calls.
- The optional AI assistant is an external processing boundary: any selected plaintext sent to an LLM provider leaves the human conversation's E2EE boundary. Require explicit opt-in/disclosure and group-participant consent policy, minimize context, and label assistant output. Never automatically export a group's history or sync BYOK secrets to other users/devices.

Deliver a security ADR, data-flow diagrams, protocol/library integration tests and external review before an E2EE release claim. Plaintext local fixtures may be useful during development but must be visibly development-only and cannot satisfy a private-messenger release gate.

## 4. Phased delivery and acceptance gates

Every phase includes tests and failure handling. Local mocks prove client behavior only; they cannot establish real authentication, delivery, calls, E2EE interoperability or production reliability.

### Phase 0 — Product contracts, local foundation and security design

**Build locally now:**

- Define account/device IDs, conversation types, membership roles, message IDs, attachment descriptors, transport envelopes, call IDs and event schema versions. Keep assistant turns and messenger messages distinct.
- Write identity ADR: phone verification versus username/email/passkey strategy, discovery consent, recovery, device ownership and eventual multi-device policy. Design onboarding/logout/account-switch isolation without requiring a live identity service for UI tests.
- Design SQL schema/migrations, durable drafts/outbox and lifecycle ownership. Preserve old AI sessions with provenance; fake sender names or demo history must never enter real accounts.
- Build Android routes and accessible empty/error/loading states; use an explicitly labeled, isolated local development mode and scripted peers.
- Define backend API/WebSocket/signaling contracts and the threat-model/library selection gate above. Prototype reviewed encryption integration in a disposable test harness without treating it as audited production crypto.

**Backend/infrastructure blockers:** real identity verification/recovery, authorized server endpoints, protocol/key directory, deployment credentials and a reviewed security design.

**Acceptance:** migration fixtures and file-backed reopen tests preserve existing history; account/device/conversation isolation is proven; contract fixtures have versioning/error semantics; mock behavior is labeled; security decisions and infrastructure owners are recorded.

### Phase 1 — Accounts, contacts and reliable encrypted 1:1 text

**Build locally now:**

- Registration/login/verification UI, secure token storage interface, refresh/logout/revocation state machine and expiry tests. No guessed credentials or hard-coded tokens.
- Android address-book access only after a contextual permission request. Permission denial/revocation must leave username/invite/manual contact entry usable; avoid uploading the whole address book by default. Define normalization and discovery consent. Plain hashes of phone numbers are enumerable and not a privacy-preserving discovery scheme.
- Local 1:1 timeline, compose/retry/cancel states, blocking UI and durable transactional message-plus-outbox writes. Offline send persists before network access and survives process death.
- Separate connection states: connecting, connected, reconnecting, offline, authentication-required. Use heartbeat deadlines, exponential backoff with jitter and caps, reachability hints, bounded queues and lifecycle-aware Android scheduling; do not rely on a permanent background socket.
- Send stable client message/idempotency IDs. Server acceptance means durable server commit, not merely WebSocket write. Retry the same operation after ambiguous timeout; server deduplicates. Model queued/sending/server-accepted/recipient-delivered/read/failed explicitly; delivered requires a recipient-device acknowledgement after durable receive, read requires a recipient action and consent policy. Define aggregation for future multiple recipient devices.
- Resume from durable per-stream cursors, repair gaps via history API and handle cursor expiry/full resync. Apply incoming messages and cursor in one transaction; deduplicate duplicates and tolerate out-of-order events without losing messages. Define per-conversation ordering rather than pretending global wall-clock order.
- Bind sessions to authenticated devices, replenish prekeys, encrypt before enqueueing for transport, and validate recipients/device-key changes through the selected implementation.

**Backend/infrastructure blockers:** identity/verification provider, TLS API/WebSocket service, durable message/ack store, auth/device authorization, key directory, discovery service and deployment/monitoring.

**Acceptance:** two real staging Android devices register and exchange encrypted text; airplane mode, process restart, server restart, token expiry, network switching, duplicate sends/acks, sequence gaps and revocation produce no loss or duplicate visible messages. Unauthorized users cannot fetch conversations or inject acks. Phone enumeration/permission-denial tests pass. No plaintext content appears in server storage/logs; cryptographic interoperability and identity-change behavior are tested.

### Phase 2 — Groups, presence, typing and read states

**Build locally now:**

- Group create/invite/join/leave/remove flows, role-based admin UX, participant list, mute/archive and configurable group-size limits. Define invitations, membership revisions and who can add participants.
- Shared tests for concurrent membership changes, revoked members, stale revisions and rekeying. Use the reviewed implementation's supported group approach; do not derive a custom group cipher from 1:1 sessions.
- Typing events with throttling and TTL; presence as expiring best-effort state rather than guaranteed availability. Make online/last-seen/read visibility configurable and honor block relationships. UI differentiates stale state and hidden state.
- Per-recipient group delivery/read summaries without implying every member has received a message based only on server acceptance. Define who sees old history after joining and what former members retain.

**Backend/infrastructure blockers:** authoritative membership/role enforcement, atomic membership/key-distribution changes, durable group fan-out, per-recipient receipts and ephemeral presence service.

**Acceptance:** staging multi-user tests cover concurrent joins/removals, offline members, reconnect, receipt privacy and blocked users. Removed members receive no new envelopes/keys; new members gain only permitted history. Expired typing/presence disappears. A secure group-library integration is a release prerequisite.

### Phase 3 — Media and Android push/background reliability

**Build locally now:**

- Android picker/camera permission flows, attachment preview, bounded size/type validation, compression, encrypted attachment descriptors and local upload/download progress. Avoid broad storage permission where platform pickers suffice.
- Resumable upload state machine with retry/idempotency, cancellation, integrity checks, expired-grant renewal and durable recovery. Publish an attachment message only after upload completion; garbage-collect abandoned objects according to retention policy.
- Notification channels, Android notification permission/denial UX, preview privacy, mute/group settings and deep links. WorkManager catches up from the server cursor after wakeup; deduplicate push notifications and handle foreground/background/process-death states.

**Backend/infrastructure blockers:** private object storage/CDN, scoped short-lived upload/download grants, quotas/cleanup jobs, FCM project/server credentials and delivery workers. Notifications should carry minimal opaque routing hints, not plaintext messages or keys.

**Acceptance:** staging tests include interrupted large uploads, duplicate completion, bad integrity, expired grants, unauthorized object fetches and cleanup. Physical-device FCM tests cover foreground/background, process death, denied permission, token rotation and Android battery restrictions; document force-stop/OS delivery limitations. Push loss cannot lose a message because durable sync remains authoritative.

### Phase 4 — Audio/video calls, signaling and call history

**Build locally now:**

- Integrate a maintained WebRTC stack behind platform adapters; implement permission denial, mic/camera toggle, speaker/Bluetooth routing, lifecycle cleanup and an explicit call state machine: inviting/ringing/connecting/active/reconnecting/ended/declined/missed/failed.
- Define authenticated signaling messages for invite/accept/reject/cancel, SDP offer/answer, ICE candidates, timeout and hangup, keyed by stable call ID. Handle duplicate/out-of-order signaling, simultaneous calls, busy state and caller cancellation before pickup. Authorization must use conversation membership and device ownership.
- Persist local call history with participants, direction, timestamps, terminal reason and duration; reconcile authoritative call events across reconnect, avoid duplicate missed calls and define retention/deletion. Call history is metadata, not a recording feature.
- Test signaling with local peers and a local STUN/TURN setup if available. These experiments do not prove public-network reachability. Plan Android incoming-call notifications, supported foreground-service/telecom integration and background execution restrictions explicitly.
- Deliver 1:1 audio first, then 1:1 video; gate group calls separately. Select and operate an SFU for scalable group calls rather than promising unlimited peer-to-peer mesh. Review SFU media-E2EE support before making equivalent privacy claims.

**Backend/infrastructure blockers:** real signaling/routing service, incoming-call push, operated STUN/TURN with short-lived scoped credentials, relay capacity/egress budget and monitoring; SFU infrastructure and participant-auth/media security for group calls. STUN discovers paths; TURN relay is essential for restrictive NAT/firewall cases and cannot be omitted from a reliable call product.

**Acceptance:** staging calls work between physical devices on separate Wi-Fi/mobile networks, including forced TURN relay, network handoff, permission denial, background ringing, busy/reject/missed-call paths, expiry and reconnect. No microphone/camera leak after termination; call history agrees on outcome without exposing media. Measure call setup success, latency and relay usage; record limitations and security review. Local loopback/WebRTC demo success is not a calling release gate.

### Phase 5 — Abuse defenses, privacy review and Android production release

Abuse protection begins with Phase 1; this phase hardens it for release rather than postponing it.

**Build locally now:**

- Block/report/mute, contact-request controls, stranger/group-invite restrictions, privacy settings, account export/deletion UX and recovery warnings. Reporting sends only user-selected evidence with consent, not a silent plaintext backdoor.
- Bound parsing, message/attachment sizes, queues, reconnect attempts and notification rates; test malformed payloads and hostile peers. Ensure diagnostic redaction and crash-report opt-in/policy.
- Android accessibility, migration/upgrade, battery/data budgets, low-connectivity soak tests, lifecycle tests and supported API/device matrix. CI requires populated unit/device test results; NO-SOURCE or skipped suites are not passing evidence.
- Signed release artifact, secure signing custody, dependency/advisory review, store privacy declarations, retention/deletion policy, support and rollout/rollback runbooks.

**Backend/infrastructure blockers:** enforced limits per account/device/IP for registration, verification attempts, contact lookup, invites, messages, media and calls; anti-enumeration responses, verification anti-fraud/spend caps, TURN allocation limits, moderation operations, security audit, production capacity, backups/restore drills and incident response. Client-only rate limiting is not an abuse defense.

**Acceptance:** penetration/abuse tests exercise authorization, key-directory trust, discovery enumeration, spam, token replay/revocation and malformed envelopes. Security review closes critical findings; staging soak/load/failure tests meet explicitly approved SLOs and cost ceilings. Restore and deletion workflows are tested. Android release is approved only after real backend, push, media, text/group and 1:1 calling gates are evidenced; deferred group calls or multi-device support are clearly disclosed rather than implied.

### Phase 6 — Future iOS, desktop and linked devices

**Build locally now:** portable contracts and protocol fixtures; capability interfaces for secure storage, push, calls and lifecycle. Avoid premature toolchain/dependency changes in the Android milestone.

**Future delivery/blockers:** add actual iOS targets/app with Keychain, APNs, background/call integration and iOS WebRTC adapters; add a packaged desktop app with OS secure storage, notifications, media devices, signed updates and lifecycle integration. Toolchains, platform signing/accounts and distribution infrastructure are required. Audit Signal implementation compatibility on each platform.

Design linked-device enrollment/approval, per-device keys and delivery fan-out, encrypted history transfer, key-change visibility, device revoke, logout isolation and encrypted-backup recovery before enabling multiple devices. Never copy raw private keys or BYOK secrets to a new device as a shortcut.

**Acceptance:** Android-to-iOS/desktop encrypted-message and call interoperability, independent offline devices, membership changes, enrollment/revoke and protocol-version compatibility pass on real clients. Each platform has its own security, background, accessibility, signing and upgrade evidence. Feature parity is declared by capability matrix, not assumed from shared code.

## 5. Optional AI assistant track

Keep current Ktor provider adapters/BYOK storage as a separate capability while prioritizing messenger milestones. Existing provider parsing, cancellation, credential storage and durable assistant-turn work remains useful but is not a messaging release gate unless the assistant is enabled in that release.

- Assistant invocation is explicit and scoped to user-selected context, with provider-processing/possible-cost disclosure. Human sending remains functional with no provider key or provider outage.
- Label AI output and author provenance; choose whether it is local-only or deliberately shared as a normal human-authorized encrypted conversation message. A provider response never earns a human delivered/read tick by itself.
- Retain provider contract tests, SSE framing/error handling, bounded retries, credential redaction, secure local key storage and opt-in live validation. Do not automatically replay ambiguous paid requests via the messenger outbox.
- Disable the capability until context consent, group policy and security disclosure are implemented. BYOK credentials are device-local and never part of messenger synchronization or protocol key material.

## 6. What can proceed now versus what blocks release

| Can build/test locally now | Requires backend/infrastructure or external review |
|---|---|
| Domain/schema design, migrations, lifecycle/navigation, isolated demo peers | Real account verification/recovery, identity/device authorization and secure deployment |
| Outbox/reconnect/cursor/ack state machines with faults and contract doubles | Durable server acceptance, cross-device delivery, history repair and production fan-out |
| Contact permission and manual contact/invite UX | Consent-based discovery, enumeration defenses and privacy/legal policy |
| Group membership/receipt UI and protocol fixtures | Authoritative membership enforcement and reviewed group key distribution |
| Media picker, encryption harness, upload recovery UI | Authorized encrypted object storage/CDN, cleanup, quotas and cost controls |
| Notification handling and Android scheduler tests | FCM/APNs projects, server delivery and real-device background evidence |
| Call state/history and local WebRTC/signaling experiments | Reachable signaling, STUN/TURN operation, incoming push, relay budgets and eventual SFU |
| Block/report/privacy UI, bounded parsing, redaction tests | Server rate limits, moderation operations, retention/deletion enforcement and security audit |
| Reviewed-library feasibility and crypto integration tests | Threat-model approval, security review and real end-to-end interoperability |

Maintain an implementation ledger per phase: owner, client PRs, server PRs, environment, test evidence, security sign-off and unresolved blockers. Mocks, green compilation and architectural diagrams cannot close infrastructure-dependent gates. Do not advertise human messaging, E2EE or calls until their staging/device evidence exists.

## 7. Verification and go/no-go evidence

### This documentation-only change

From the repository root:

```bash
git diff --check
git diff -- docs/NEXT_PHASE_PLAN.md
git status --short
```

If this plan is still untracked, ordinary `git diff` will omit its content. Review the file directly and use `git diff --no-index --check /dev/null docs/NEXT_PHASE_PLAN.md` to check its whitespace without staging it. Review existing unrelated working-tree changes without modifying, reverting or attributing them to this planning pass. No source/build change or new build execution is required for this documentation request.

### Implementation verification to perform as phases land

Existing Android/JVM build tasks can support local regression verification; they do not demonstrate messenger functionality:

```bash
JAVA_HOME=/home/shafi/.jdks/jbr-17.0.14 bash ./gradlew :shared:jvmTest :app:testDebugUnitTest :shared:verifySqlDelightMigration :app:lintDebug :app:assembleDebug --rerun-tasks --console=plain
```

Confirm task/toolchain availability when executing and retain populated test reports plus migration fixtures. Add Android instrumentation and actual process-relaunch tests as client implementation lands. Backend integration, staging deployment, push, signaling/TURN, load and security test harnesses are new deliverables; no executable commands or successful outcomes are claimed for services that do not yet exist.

Release checklist:

- [ ] Account/device authentication, recovery, authorization and revoke pass against staging.
- [ ] Address-book denial/revocation/manual discovery and anti-enumeration policies pass.
- [ ] Encrypted 1:1/group delivery, outbox/reconnect/cursor/ack durability and membership changes pass on real devices.
- [ ] Media confidentiality, upload recovery, authorization and cleanup pass.
- [ ] Android push/background catch-up passes with documented OS limitations.
- [ ] Audio/video calls pass real NAT/TURN, background, lifecycle and call-history tests; deferred group calls are disclosed.
- [ ] Presence/typing/read privacy and stale-state semantics are tested.
- [ ] Reviewed Signal implementation, identity/key lifecycle, threat-model and external security review gates are closed; no custom crypto or unsupported E2EE claims.
- [ ] Abuse/rate limits, privacy/retention/deletion, operations, backup/restore and production ownership are verified.
- [ ] Signed Android candidate passes device/upgrade/accessibility/soak gates with rollback evidence.
- [ ] AI remains optional, explicitly consented and separate from human transport; future platforms/linked devices are not presented as implemented.

Recommended first implementation slice: Phase 0 contracts and non-destructive messenger schema/outbox foundations with migration and fault tests, alongside backend identity/transport and security ADRs. Do not start by relabeling the AI demo as live messaging or adding unconnected call screens.
