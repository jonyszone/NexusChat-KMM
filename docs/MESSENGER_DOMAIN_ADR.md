# ADR: Messenger domain foundation and infrastructure boundaries

Status: Accepted for the first local vertical slice; backend slice implemented
Date: 2026-10-07

## Decision

The shared `commonMain` messenger domain is the protocol-neutral contract for the first local slice. It defines serializable users, devices, conversations, members, messages, delivery states, ordering/idempotency metadata, durable outbox operations, presence/typing, and call signaling state/signal models. `RealtimeTransport`, `ContactsRepository`, `MediaGateway`, `PushGateway`, `CallsGateway`, and `MessengerRepository` are dependency-inversion boundaries; no implementation in this slice claims network delivery.

Human messaging is independent of the existing optional AI assistant. `MessageKind.ASSISTANT` and `assistantGenerated` preserve provenance when an assistant is explicitly enabled, but an LLM response cannot advance a human message's delivery state. Registration, messaging, and calling must not require a BYOK provider key.

Delivery state is monotonic (`pending -> sending -> sent -> delivered -> read`) with an explicit retry path (`failed -> pending`). `sent` means a future backend has durably accepted the operation; `delivered` and `read` require recipient acknowledgements. `clientMessageId` and `idempotencyKey` remain stable across retries. Per-conversation server sequence numbers are authoritative for ordering; local unsequenced messages remain visible after sequenced messages until reconciliation.

## Backend boundary (development-only slice)

The isolated `server/` Ktor JVM module exposes `GET /healthz` and WebSocket `/v1/realtime` with protocol-version-1 typed client/server envelopes. Message acceptance returns a correlated ack; malformed/version mismatch and repository rejections return structured errors. The history request returns acknowledgement metadata after a sequence cursor. `DevelopmentSessionAuthenticator` accepts a constrained `X-Dev-User-Id` request header and the application currently defaults to it; it is a development stub, not production authentication. `MessageRepository` has a synchronized atomic-file implementation exercised across object restart and a volatile in-memory scripted implementation. The file implementation supports a local test/development persistence scenario only; it is not a managed production database, and multi-process concurrency/durability guarantees are not claimed.

Tests cover envelope version/default and serialization round-trip, membership rejection, retry idempotency, correlated ack/error routing, and cursor history. No client WebSocket adapter or SQLDelight realtime outbox/inbound tables are implemented. Production auth, E2EE, push, media, and calls are not implemented.


## Signaling and calls boundary (later infrastructure)

`Call` and `CallSignal` model membership-authorized invite/accept/reject/SDP/ICE/hangup state and duplicate/out-of-order revisions. Actual signaling service, WebRTC platform adapter, STUN/TURN operation, incoming-call push, relay credentials, and any group-call SFU are explicitly later work. These models are not evidence that calls work on a real network.

## E2EE boundary (later security/infrastructure)

This slice carries message metadata and plaintext test bodies only as local domain fixtures. It does not implement encryption, key management, identity keys, prekeys, device trust, group rekeying, encrypted media, or safety-number UX. A reviewed and maintained Signal Protocol implementation, platform compatibility/licensing decision, threat-model review, and interoperability/security testing are required before an E2EE claim. TLS, server storage, metadata, backups, notification previews, and WebRTC media privacy remain separate decisions.

## Consequences

The shared and server JVM tests prove deterministic local state transitions, envelope serialization, in-memory idempotency, acknowledgement flow, health, and WebSocket routing only. They do not prove backend authorization, cross-device delivery, E2EE, WebRTC connectivity, TURN reachability, push delivery, or production reliability. Existing AI/BYOK code remains untouched and optional.
