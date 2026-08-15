# ADR 0002: Distinguish durable commands from lossy telemetry

Status: accepted.

Order commands are stored in a transactional outbox and delivered at least once. Stable `(orderId, orderUpdateId)` identities, API idempotency keys, simulator duplicate handling, and conditional database transitions make retries safe. Visualization and fork telemetry use a latest-value buffer because intermediate samples have no business value. The UI interpolates the newest pose and PostgreSQL samples it at 2 Hz. This protects command latency and database capacity at the cost of intentionally incomplete pose history.
