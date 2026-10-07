# ADR: Messenger domain foundation and infrastructure boundaries

Status: Accepted for the first local vertical slice; backend slice implemented
Date: 2026-10-07

## Decision

The shared `commonMain` messenger domain is the protocol-neutral contract for the first local slice. It defines serializable users, devices, conversations, members, messages, delivery states, ordering/idempotency metadata, durable outbox operations, presence/typing, and call signaling state/signal models. `RealtimeTransport`, `ContactsRepository`, `MediaGateway`, `PushGateway`, `CallsGateway`, and `MessengerRepository` are dependency-inversion boundaries; no implementation in this slice claims network delivery.

Human messaging is independent of the existing optional AI assistant. `MessageKind.ASSISTANT` and `assistantGenerated` preserve provenance when an assistant is explicitly enabled, but an LLM response cannot advance a human message's delivery state. Registration, messaging, and calling must not require a BYOK provider key.

Delivery state is monotonic (`pending -> sending -> sent -> delivered -> read`) with an explicit retry path (`failed -> pending`). `sent` means a future backend has durably accepted the operation; `delivered` and `read` require recipient acknowledgements. `clientMessageId` and `idempotencyKey` remain stable across retries. Per-conversation server sequence numbers are authoritative for ordering; local unsequenced messages remain visible after sequenced messages until reconciliation.

## Backend boundary (development-only slice)

The isolated `server/` Ktor JVM module exposes `GET /healthz`, scripted `GET /dev/v1/account`, `/contacts`, `/conversations/direct/{userId}`, and WebSocket `/v1/realtime` with protocol-version-1 typed client/server envelopes. These account/contact/direct-conversation routes use the explicitly seeded Alice/Bob/Mallory development fixture; `DeviceRecord` is fixture metadata only and does not represent authenticated device enrollment. The message repository's default membership map is derived from that same account fixture, so send/history operations reject accounts outside a conversation's member set. A caller can still inject a distinct message repository in tests or development composition.

`DevelopmentSessionAuthenticator` accepts a constrained `X-Dev-User-Id` request header and the application defaults to it; this is a development stub, not production authentication. The message repository has a synchronized atomic-file implementation exercised across object restart and a volatile in-memory implementation. A separate atomic-file account repository is exercised across restart. Both file stores support local test/development persistence only; they are not managed production databases, and multi-process concurrency/durability guarantees are not claimed. Scripted account APIs are not registration, login, contact discovery, device management, or user-controlled social graph functionality.

Tests cover envelope version/default and serialization round-trip, file account fixture restart, file message restart/idempotent retry, membership rejection, correlated ack/error routing, cursor history, and the shared client boundary's fake-transport serialization, ack correlation, cursor catch-up, reconnect, and unauthorized-error behavior. Shared common code now contains client-facing serializable account/device/contact/membership models and account/direct-conversation repository interfaces. The Rust protocol adapter is implemented behind `RealtimeTransport`: `KtorRustRealtimeTransport` sends only `X-Dev-User-Id`, while `DefaultRealtimeMessengerClient` owns versioned envelopes, send/ack/history/reconnect commands, typed remote errors, reconnect state, and monotonic cursors. `DefaultRealtimeMessengerRepository` delegates messaging operations through that client. No production authorization or deployment is present. SQLDelight realtime outbox/inbound tables, production auth, TLS deployment, account recovery, E2EE, push, media, and working calls are not implemented.


## Signaling and calls boundary (later infrastructure)

`Call` and `CallSignal` model membership-authorized invite/accept/reject/SDP/ICE/hangup state and duplicate/out-of-order revisions. Actual signaling service, WebRTC platform adapter, STUN/TURN operation, incoming-call push, relay credentials, and any group-call SFU are explicitly later work. These models are not evidence that calls work on a real network.

## E2EE boundary (later security/infrastructure)

This slice carries message metadata and plaintext test bodies only as local domain fixtures. It does not implement encryption, key management, identity keys, prekeys, device trust, group rekeying, encrypted media, or safety-number UX. A reviewed and maintained Signal Protocol implementation, platform compatibility/licensing decision, threat-model review, and interoperability/security testing are required before an E2EE claim. TLS, server storage, metadata, backups, notification previews, and WebRTC media privacy remain separate decisions.

## Consequences

The shared and server JVM tests prove deterministic local state transitions, envelope serialization, in-memory idempotency, acknowledgement flow, health, and WebSocket routing only. They do not prove backend authorization, cross-device delivery, E2EE, WebRTC connectivity, TURN reachability, push delivery, or production reliability. Existing AI/BYOK code remains untouched and optional.
