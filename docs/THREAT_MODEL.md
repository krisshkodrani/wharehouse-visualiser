# Threat model

## Trust boundaries and assets

Inventory correctness, transport commands, AGV safety state, provider credentials, and dispatch audit data are the important assets. The browser-to-backend HTTP boundary, backend-to-broker connection, AGV MQTT identity, database connection, and optional OpenRouter call are separate trust boundaries.

## Addressed in v0.1.0

- Secrets come from ignored environment files and are never returned to the browser.
- Public host ports bind to loopback in the local Compose profile.
- VDA payloads are schema-validated before use or publication.
- Idempotency keys and guarded transitions reduce duplicate-command impact.
- Correlation IDs and rejection metrics support incident investigation.

## Explicitly not production-safe

There is no user authentication, authorization, TLS, AGV certificate identity, broker ACL isolation, rate limiting, audit retention policy, or safety PLC integration. A malicious local client can issue operational commands, and `POST /api/v1/client-logs` lets one write into the server's log file: the endpoint exists so browser diagnostics and backend records share a stream, and it bounds the damage (200 entries per batch, 512 characters per field, truncating rather than rejecting, three permitted levels) without preventing it. Production use requires the same identity-aware edge as every other route, plus a per-client rate limit. Production use requires an identity-aware edge, mutual TLS, per-topic broker ACLs, network segmentation, encrypted secrets, rate limits, immutable audit retention, and an independent safety layer.
