# Roadmap to target state

This document scores the repository against the target-state engineering requirements and
sequences the remaining work. It is deliberately blunt about gaps: the point of the exercise
is that a reviewer can see what is real, what is partial, and what is deferred on purpose.

Assessed against commit `93c2910` plus the current working tree. Baseline measurements:
21 backend classes in one flat package (836-line `WarehouseStore`), 24 JVM tests,
4 browser unit suites, 3 e2e specs, 22 Flyway migrations, 6 metric families, zero Java enums.

## Headline

| Verdict | Sections | Share |
|---|---|---|
| Implemented | 17 | ~28% |
| Partial | 26 | ~43% |
| Not started | 17 | ~28% |

Story view (§4) has since landed, which is why §4 reads Done above. The rest of milestone M3
— one contextual panel, routes drawn in Babylon, splitting `WarehouseScene.ts` — is untouched.

The protocol and reliability core is the strongest part of the project and is close to the
target already: VDA 5050 as a separate contracts module with schema validation on both
directions, a transactional outbox for commands, coalesced 20 Hz telemetry with browser-side
interpolation, A* routing with a layout validator, Problem Details, idempotency keys, and a
durable `execution_event` audit table.

The three weakest areas are, in order of how much they cost in review:

1. **No backend layering and no explicit state machines** (§29–§32). Everything lives in
   `com.example.warehouse`, and vehicle/task status is written by 54 hand-rolled SQL
   `status='...'` updates with no enum and no legal-transition check anywhere.
2. **Battery and charging are not a domain** (§17–§26). The entire policy is the literal
   `battery>=25` repeated in three SQL strings; the simulator charges to 100 at a hardcoded
   rate, and nothing knows the difference between "parked" and "intentionally charging and
   unavailable".
3. **Test depth** (§54–§55). 24 JVM tests with no integration layer at all — no
   Testcontainers, no REST idempotency test, no WebSocket test, no simulator failure tests.

## Scorecard

### Product and UX (§2–§10)

| # | Requirement | Status | Evidence / gap |
|---|---|---|---|
| 2 | Product identity | Done | README and docs frame it as a control tower, not a WMS |
| 3 | Domain model separation | Partial | `transport_order`/`transport_task` normalised in V11, but `job` is still used as a synonym — `JobExecutionService`, `GET /jobs/{id}`, `nextQueuedJob` |
| 4 | 75–85% 3D | Done | Story view is the default and hides both rails; the canvas measures >95% of the viewport width, asserted in `story-view.spec.ts` |
| 5 | Execution screen | Partial | Story view adds a narrated "now" line, a six-stage pipeline strip, and a protocol proof line. Still no per-action "node N of M" progress |
| 6 | One contextual panel | Missing | Story view sidesteps the problem rather than solving it: Engineer view still mounts both rails, and there is no click-to-context for vehicle/pallet/rack/route |
| 7 | Diagnostics drawer | Missing | Reset, simulation speed, pause and receive are consolidated into one Presenter menu, and "How this works" explains the flow. The synthetic `POST /demo/events` fault injection was removed rather than kept: it fabricated protocol rejections the backend had not actually produced. Real injection belongs in the simulator (§42), and there is no diagnostics surface yet |
| 8 | Execution timeline | Partial | `execution_event` (V22) carries order/task/vehicle/correlation/vdaOrder/orderUpdateId; UI has an execution journey and activity feed. No node-reached or charging events |
| 9 | Raw VDA on demand | Done | VDA workbench dialog: summary + schema status + JSON behind a button |
| 10 | Base/horizon in 3D | Missing | `released` is surfaced in the inspection table only. The scene draws no route lines at all |

### Frontend architecture (§11–§16)

| # | Requirement | Status | Evidence / gap |
|---|---|---|---|
| 11 | OpenUI5 owns shell | Done | No warehouse geometry in views |
| 12 | Babylon owns geometry | Done | Visual projection only; inventory never driven by animation |
| 13 | Clean visualization API | Partial | 9 public methods, none matching the target names. No `highlightRoute`, `showOrder`, `attachLoad`/`detachLoad`, `selectVehicle`, `showReservation` |
| 14 | Entity structure | Partial | Forklift and arm are proper `TransformNode` hierarchies (`armKinematics.ts`); no `VehicleVisual`/`RackVisual`/`RouteVisual` split — `WarehouseScene.ts` is 1759 lines |
| 15 | GLB assets | Missing | All primitives, no `AssetLoader`, no graceful-degradation path |
| 16 | Forklift visual states | Partial | Pose, heading, fork height, load attach done; no selected/blocked/paused/offline states |

### Battery and charging (§17–§26)

| # | Requirement | Status | Evidence / gap |
|---|---|---|---|
| 17 | Battery model | Missing | Only `battery>=25` hardcoded in `WarehouseStore` (3 sites). No 30/15/10/85 thresholds, nothing configurable |
| 18 | Charging policy | Missing | No policy object; charging is a side effect of parking |
| 19 | Charging as a state | Partial | `CHARGING`/`PARKING`/`PARKED` exist as strings; no `GOING_TO_CHARGE`, no `AVAILABLE`/`BLOCKED`/`OFFLINE`, no enum |
| 20 | Charging station model | Partial | Modelled as `location.type='PARKING_CHARGING'` with capacity/occupied/reserved; no `occupiedBy` |
| 21 | Charging workflow | Missing | No threshold evaluation, no charging task, no route-to-charger |
| 22 | Charging timeline | Missing | No charging events reach `execution_event` |
| 23 | Consumption simulation | Partial | `BatteryModel` + tests, per-metre rate configurable; no idle/driving/loaded/lifting split; charge rate `5d/60d` and target 100 are hardcoded |
| 24 | Battery-aware assignment | Partial | The `battery>=25` filter only |
| 25 | Energy reserve | Missing | No estimate, no "can it still reach a charger" check |
| 26 | Charging UX | Missing | No charging detail panel |

### Backend architecture (§27–§41)

| # | Requirement | Status | Evidence / gap |
|---|---|---|---|
| 27 | Commands vs telemetry | Done | Outbox for commands, latest-wins coalescing for telemetry |
| 28 | Visualization telemetry | Done | 20 Hz in, 500 ms persist, 150 ms render delay interpolation, deltas not snapshots |
| 29 | Layering | Missing | Flat `com.example.warehouse`; no api/application/domain/infrastructure |
| 30 | Organization | Missing | As above |
| 31 | Domain isolation | Missing | `WarehouseStore` mixes SQL, domain rules, and DTO mapping in 836 lines |
| 32 | Explicit state machines | Missing | Zero enums in the codebase; 54 raw SQL status writes; invalid transitions are silently possible |
| 33 | VDA boundary | Partial | Separate `vda5050-contracts` module is right; mapping is still spread across `PlanningService`, `DispatchService`, `MqttGateway` |
| 34 | VDA validation | Done | Schema-validated in both directions, rejections eventful and counted |
| 35 | MQTT | Done | Reconnect, explicit QoS, retained-control cleanup, centralised topics, connection state |
| 36 | Transactional outbox | Done | With retries, failure state, and metrics |
| 37 | Idempotency | Partial | `IdempotencyService` + V12 for the API; instant actions now consumed once by `actionId`. Repeated completion and inventory transitions untested |
| 38 | Routing | Done | A*, blocked nodes/edges, `LayoutValidator`, 6+6 tests |
| 39 | Multi-AGV readiness | Partial | Deliberately single-vehicle; `FL-01` and `PARK-01` are hardcoded in the parking/charging paths. The dead `FL-02`/`FL-03` switch is gone, so the code matches the documented fleet |
| 40 | Traffic management | Deferred | Correctly not implemented |
| 41 | Inventory invariants | Partial | Transactional and never animation-driven; no invariant tests |

### Platform (§42–§53)

| # | Requirement | Status | Evidence / gap |
|---|---|---|---|
| 42 | Simulator | Partial | Independent process; deterministic orders, actions, pause/resume/cancel, battery, charging, reconnect. Missing blocked-route, order-rejection, stale-update, malformed-payload injection |
| 43 | AI boundary | Done | `PlacementAdvisor`, deterministic mock default, failures create nothing |
| 44 | REST API | Partial | `/api/v1`, record DTOs, Problem Details, OpenAPI. No machine-readable error codes beyond the type URI; frontend client is hand-written |
| 45 | WebSockets | Partial | Typed envelope with eventType/entityId/correlationId/payloadVersion/epoch. Reconnect reconciliation not proven |
| 46 | Database | Done | Flyway-only, transactional, indexed, pose telemetry kept off the hot path |
| 47 | Audit trail | Partial | `execution_event` written and read; charging and node-level steps absent |
| 48 | Observability | Partial | 6 metric families of ~17 asked for; correlation ID in MDC and Problem Details. No order/task counters, charging or low-battery metrics, latency timers, or outbox-depth gauge |
| 49 | OpenTelemetry | Missing | No dependency, no config |
| 50 | Error taxonomy | Partial | 3 exception types, no taxonomy |
| 51 | Security posture | Done | `SECURITY.md`, `THREAT_MODEL.md`, broker auth, 1883 bound to loopback, limitations stated |
| 52 | TypeScript conventions | Partial | `strict: true` and typecheck in CI; no ESLint/Biome, no formatter, no lint gate |
| 53 | Java conventions | Partial | Java 21, constructor injection, records. No enums, no Spotless/Checkstyle/SpotBugs, one god class |

### Delivery (§54–§61)

| # | Requirement | Status | Evidence / gap |
|---|---|---|---|
| 54 | Testing | Missing | 24 JVM + 33 browser unit + 9 e2e, all green. No integration layer (no Testcontainers), no state-transition, inventory-invariant, idempotency, WebSocket, charging, or simulator-failure tests |
| 55 | E2E journeys | Partial | Happy path covered by `operations-video.spec.ts`. Missing pause/resume, cancellation, rejection, blocked route, recovery, broker restart, duplicate message, AI failure, all charging journeys |
| 56 | CI | Partial | `ci.yml` runs JVM tests, typecheck, build, audit, Docker compose e2e; `security.yml` and `release.yml` exist. Missing Java static analysis, frontend lint, VDA schema check, OpenAPI drift check |
| 57 | Containers | Partial | Multi-stage, healthchecked, nginx for static assets. No `USER` directive anywhere — everything runs as root |
| 58 | Cheap deployment | Partial | Compose with only 8080 published; no TLS reverse proxy and no Lightsail runbook |
| 59 | Configuration | Partial | DB/MQTT/AI externalised. No battery, charging, pose-rate, topic-root, or OTel config; no startup validation |
| 60 | Documentation | Done | 6 docs, 2 ADRs, OpenAPI, README. Missing a battery/charging policy doc and a domain-model doc |
| 61 | Non-goals | Done | Respected — no Kubernetes, Kafka, event sourcing, or microservices |

## Milestones

Ordered so that each milestone leaves the repository demonstrably better than the last, and
so the two milestones with the highest review value come first. Every milestone ends with
`./mvnw test`, `npx tsc --noEmit`, and the e2e suite green.

### M1 — Make the architecture legible (§29–§32, §3, §53)

The single highest-value change. A reviewer opening a flat package with a 836-line store and
no enums will discount everything else in the project.

- Split into `api` / `application` / `domain` / `infrastructure` by feature, per §30. Move
  JDBC out of `WarehouseStore` into repository adapters behind ports.
- Introduce enums with legal transitions: `TaskStatus`, `VehicleStatus`, `ChargingStatus`,
  `OrderStatus`. Route every status write through a transition method that throws on an
  illegal move, and replace the 54 raw SQL status updates.
- Retire `job` as a synonym for task: rename `JobExecutionService`, `nextQueuedJob`, and the
  `/jobs/{id}` endpoint (keep a deprecated alias for one release).
- Add Spotless and Error Prone; wire both into `ci.yml`. Add ESLint (or Biome) plus a
  frontend lint job.
- Tests: a transition test per state machine, including rejected transitions.

### M2 — Battery and charging as a real domain (§17–§26, §59)

The most visible functional gap, and the requirement with the most detail behind it.

- `ChargingPolicy` in the application layer with configurable thresholds
  (`opportunity=30`, `forced=15`, `critical=10`, `target=85`), validated at startup via
  `@ConfigurationProperties` + `@Validated`.
- `GOING_TO_CHARGE` as a first-class state; charging tasks routed to a charger like any
  other movement, dispatched as a VDA order.
- `ChargingStation` with `occupiedBy`; drop the `PARKING_CHARGING` location overload.
- `EnergyRequirementEstimator` so no task is dispatched that leaves the vehicle unable to
  reach a charger. Replace the `battery>=25` SQL filter with a policy call.
- Simulator: split consumption into idle/driving/loaded/lifting; make charge rate and target
  configurable; stop at 85 rather than 100.
- Charging events into `execution_event`, and a charging detail panel per §26.
- Tests: threshold behaviour, opportunity vs forced charging, return to service, reserve
  estimation, and the full charging workflow.

### M3 — UX to the target proportions (§4–§10, §13, §16)

Partly delivered: story view is the default, the map owns the viewport, the narration and
pipeline strip answer "what is happening?", and presenter controls are consolidated. What
remains:

- Collapse Engineer view's two permanent rails into one contextual panel driven by selection;
  clicking empty floor dismisses it.
- Build the diagnostics surface (§7): raw MQTT/VDA payload viewer, internal counters, and
  fault injection driven from the **simulator** so a rejection or blocked route is a real
  protocol event. The previous UI control published a synthetic `VDA_REJECTION` event from the
  backend without any vehicle involvement, which demonstrated the UI and nothing else; it was
  removed rather than relocated.
- Draw routes in Babylon with completed/base/horizon/blocked styling, and make selection
  bidirectional between the VDA node list and the 3D scene (§10).
- Extend the visualization API to the §13 names and split `WarehouseScene` into
  `VehicleVisual` / `RackVisual` / `LoadVisual` / `RouteVisual` / `SelectionManager` /
  `CameraController`.
- Add the missing forklift visual states: selected, blocked, paused, offline.

### M4 — Test and observability depth (§48, §54, §55, §47, §50)

- Testcontainers integration layer: PostgreSQL + Mosquitto. Cover outbox publication, REST
  idempotency (same key twice, conflicting key), WebSocket event delivery, and reconnect
  reconciliation.
- Inventory invariant tests for each rule in §41.
- Simulator failure injection: blocked route, order rejection, stale update, malformed
  payload — then e2e journeys for each, plus pause/resume, cancellation, recovery, broker
  restart, duplicate delivery, and AI failure.
- Fill out the metric set to §48, add an error taxonomy, and extend `execution_event` to
  node-reached and charging steps so an order is fully reconstructable.
- Fix the local browser-unit-suite path: `playwright.config.ts` only spawns the ui5 dev
  server when `E2E_BASE_URL` is unset, and that spawn times out on port 8082. CI is
  unaffected because the docker-e2e job sets `E2E_BASE_URL`, so the QUnit suites including
  `armKinematics` do run there — but `npm run test:e2e` fails for a contributor who has not
  started Compose, which is a bad first-run experience for an open-source repo.

### M5 — Ship it (§49, §57, §58, §15, §60)

- Optional OpenTelemetry with OTLP export, off by default so local dev needs no collector.
- Non-root `USER` in all three images; re-verify healthchecks.
- Caddy in front for TLS, plus a Lightsail deployment runbook.
- GLB assets behind an `AssetLoader` with primitive fallback.
- Docs: battery/charging policy, domain model, demo walkthrough, and a decision log entry
  for each M1–M2 architectural choice — §60 asks for *why*, not just *what*.

## Open-source readiness

Already in place: the Apache 2.0 licence, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`,
`SUPPORT.md`, `THIRD_PARTY_NOTICES.md`, `CHANGELOG.md`, dependency-audit and release
workflows, and a README that states its own limitations.

Also already clean: `.gitignore` covers `.env`, `debug.log`, `ui5-*.log`, `test-results/`,
and `playwright-report/`, and none of them are tracked; the docs were reconciled to the
single-vehicle decision in the remediation work, and `docs/ARCHITECTURE.md` explains what a
second vehicle would actually require.

Checked before making the repository public:

- Full-history secret scan is clean. `git log --all --full-history -- .env` shows only
  `.env.example` was ever tracked, and every committed `OPENROUTER_API_KEY` occurrence is a
  placeholder (`your-key`, empty) or a shell variable reference — the real `.env` has never
  been committed on any branch or amended commit.
- The V17 checksum question is resolved. The migration ended with a corrupted comment line
  (binary noise, not a guard); V17 had never been committed, so removing it broke no published
  checksum chain. A local database that already applied V17 needs `docker compose down -v`.
