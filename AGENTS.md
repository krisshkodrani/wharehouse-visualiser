# Engineering contract for coding agents

## Mission and sources of truth

Build a comprehensible warehouse control-tower reference system. Product intent lives in `docs/PRODUCT.md`; runtime boundaries and invariants live in `docs/ARCHITECTURE.md`; operational behavior lives in `docs/OPERATIONS.md`. When code and documentation disagree, stop and resolve the discrepancy in the same change.

## Architecture boundaries

- `webapp` renders operator state and issues commands only through the public REST/WebSocket boundary. It never reconstructs authoritative inventory state.
- `backend` owns inventory, transport orders, tasks, runtime state, dispatch audit, and transactions.
- `simulator` behaves like an external mobile robot. It communicates through MQTT and must not access the backend database.
- `vda5050-contracts` contains wire records and pinned upstream schemas. It must not depend on backend or simulator code.
- Business transport orders are not VDA orders. Translation belongs in `DispatchService`; do not leak VDA sequencing into the public product model.

## Non-negotiable invariants

- At most one task is active for an AGV; a load has at most one active destination reservation.
- VDA `orderId` is stable for a task and `orderUpdateId` increases monotonically.
- Only released base nodes may execute; horizon nodes are planning information.
- State transitions are idempotent and conditional on valid predecessor states.
- A stale simulation epoch may update neither inventory nor vehicle state.
- Telemetry is latest-value-wins. Never put 20 Hz pose messages on the transactional command path.
- Every emitted standard VDA payload validates against the pinned v3.0.0 schema before publication.

## Change rules

- Keep controllers thin; transactions and policy belong in application services, SQL in repository adapters.
- Flyway migrations are forward-only. Never edit an applied migration; add the next numbered migration and prove upgrade from an empty database.
- Preserve legacy API projections until a documented breaking release. New errors use RFC 9457 Problem Details.
- Treat MQTT delivery as at-least-once. Commands need stable identities and consumers must tolerate duplicates.
- Do not log secrets, raw authorization headers, or OpenRouter keys. Local defaults must remain bound to loopback or private Compose networks.
- UI updates must preserve keyboard access, responsive layouts, stale-event filtering, and requestAnimationFrame-based pose interpolation.

## Verification matrix

- Java/domain/VDA change: `./mvnw test` (`mvnw.cmd test` on Windows).
- TypeScript/UI change: `npm run typecheck` and `npm run build`.
- REST, MQTT, migration, or Compose change: rebuild the affected images and run `npm run test:e2e` against Docker.
- Schema change: run contract tests for every affected VDA topic and update schema provenance checksums.
- Dependency change: run the full tests plus `npm audit --omit=dev`; explain any accepted development-only advisory.
- Documentation-only change: verify links, commands, diagrams, and `git diff --check`.

## Git and completion

- Preserve user changes and existing history. Never backdate, falsify authorship, or rewrite shared history.
- Use focused Conventional Commits: `feat`, `fix`, `refactor`, `test`, `docs`, `ci`, `chore`.
- A commit must be coherent and pass its smallest relevant gate. Commit bodies explain why and trade-offs, not a file list.
- A change is done only when behavior, tests, operational impact, public contracts, and relevant documentation agree.
