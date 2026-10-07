# NexusChat backend vertical slice

This isolated `:server` JVM Ktor module is a local development slice only.

Run without credentials or external services:

    ./gradlew :server:run

Endpoints:

- `GET /healthz` returns `ok`.
- `WS /v1/realtime` requires the development-only `X-Dev-User-Id` header.

The transport uses typed kotlinx.serialization envelopes for message send/ack, presence, typing, and call signaling. `InMemoryMessageRepository` provides user-scoped idempotency for local tests. The identity boundary is intentionally not production authentication: do not expose this server publicly or treat the header as proof of identity.

## Explicit blockers / TODOs

- [ ] Replace `DevelopmentSessionAuthenticator` with verified access/refresh session or token validation, device authorization, membership checks, expiry, revocation, abuse controls, and audit logging.
- [ ] Replace `InMemoryMessageRepository` with a durable transactional store and an outbox/fan-out design; preserve idempotency uniqueness and server sequence allocation.
- [ ] Add Redis or another broker only behind a repository/presence/fan-out interface after delivery and reconnect semantics are specified.
- [ ] Add push delivery behind `PushGateway`; no FCM/APNs credentials or calls belong in this slice.
- [ ] Add media object storage, malware scanning, upload limits, signed URLs, and lifecycle cleanup behind `MediaGateway`.
- [ ] Add WebRTC signaling authorization plus STUN/TURN provisioning and credential rotation behind `CallsGateway`; this slice does not establish media connectivity.
- [ ] Select, integrate, and review a maintained Signal Protocol implementation; add device keys, prekeys, trust UX, group rekeying, and interoperability/security tests before making any E2EE claim.
- [ ] Add protocol versioning, schema compatibility tests, rate limits, observability, reconnect/cursor repair, and production deployment policy.
