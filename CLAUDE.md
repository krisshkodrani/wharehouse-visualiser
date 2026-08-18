# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`AGENTS.md` is the authoritative engineering contract for this repo (boundaries, invariants, verification matrix, commit rules). Read it before changing behaviour; this file covers commands and the architecture you would otherwise have to reconstruct from many files.

## Commands

### Java (Maven multi-module: `vda5050-contracts` → `backend` → `simulator`)

Java 21. The wrapper self-bootstraps, so no global Maven is needed.

```powershell
.\mvnw.cmd test                              # all Java tests (./mvnw test on POSIX)
.\mvnw.cmd test -Dtest=RoutePlannerTest      # one class
.\mvnw.cmd test -Dtest=MqttGatewayTest#name  # one method
.\mvnw.cmd -pl backend -am test              # one module plus its dependencies
```

### Frontend

```powershell
npm ci
npm run typecheck    # tsc --noEmit over webapp/**/*.ts
npm run build        # bundles Babylon, then ui5 build
npm start            # ui5 dev server on :8080 — no backend proxy, so the app reports "OFFLINE DEMO"
```

`prestart`/`prebuild` run `scripts/build-babylon.mjs`, which rollups `@babylonjs/core` into `webapp/vendor/babylon.js`. That file is generated output, not source — never edit it.

### Tests

Playwright is the only frontend test runner; `npm test` is an alias for `npm run test:e2e`.

```powershell
npx playwright test test/e2e/story-view.spec.ts   # one spec
npx playwright test -g "renders the seeded"       # one test by title
npx playwright test --headed                      # or npm run test:e2e:headed
```

- Without `E2E_BASE_URL`, Playwright starts `ui5 serve --port 8082` itself. The API-mocking specs (`warehouse`, `story-view`, `unit-suite`) pass there; `operations-video` skips itself because it needs the live stack.
- Full stack: `docker compose up --build --wait -d`, then `E2E_BASE_URL=http://localhost:8080 npm run test:e2e` (this is what CI's `docker-e2e` job does).
- `workers: 1` is deliberate — every spec drives the same single forklift, backend, and database.
- Browser unit tests are QUnit modules in `webapp/test/unit/*.qunit.ts`, aggregated by `unitTests.qunit.ts` and executed inside `test/e2e/unit-suite.spec.ts`. **A new `*.qunit.ts` file only runs if it is imported from `unitTests.qunit.ts`.** `npm run test:unit` just serves them at <http://localhost:8081/test/unit/unitTests.qunit.html> for manual inspection.

#### Handling-precision analysis

Opt-in, needs the live stack, and never runs in CI:

```powershell
$env:E2E_BASE_URL="http://localhost:8080"; $env:E2E_VIDEO="on"
npx playwright test test/e2e/forklift-precision.spec.ts
node scripts/analyze-handling.mjs      # frames + fork-profile.txt in artifacts/handling
```

The spec records one pick-and-drop on an empty warehouse and writes `fork-samples.json` and `animation-telemetry.json` next to `video.webm`, all stamped with the page clock. `scripts/analyze-handling.mjs` pulls the frame for each handling event and prints the fork travel as a table.

It also blacks out the viewport once and records the page time. Playwright starts recording before `performance.now()` begins, so without that marker every extracted frame lands 0.7–2 s early — measured, not theoretical. `E2E_ZOOM_STEPS` reframes the camera; zoom moves toward the building centre rather than the vehicle, so raising it can push the forklift out of shot.

### Running the stack

```powershell
Copy-Item .env.example .env
docker compose up --build --wait     # UI on http://localhost:8080
docker compose down -v               # wipes the demo database and broker data
docker compose up -d --wait postgres mosquitto   # dependencies only, for host-side backend runs
```

A host-run backend defaults to port 8088 (`SERVER_PORT`); inside Compose it listens on 8080 and nginx proxies `/api/` and `/ws` to it. `AI_PROVIDER=mock` is the deterministic default; `openrouter` is the only alternative.

## Architecture

### Module boundaries (enforced, not conventional)

| Unit | Owns |
| --- | --- |
| `backend/` | Inventory, transport orders/tasks, routing, runtime state, dispatch audit, transactions, REST + STOMP + MQTT |
| `simulator/` | An external mobile robot. Talks MQTT only; **never** touches the database |
| `vda5050-contracts/` | Wire records (`Vda5050.java`), `VdaSchemaValidator`, pinned upstream v3.0.0 JSON schemas. Depends on nothing else in the repo |
| `webapp/` | Operator UI. Reads state only through REST/WebSocket; never reconstructs authoritative inventory |

Business **transport orders** (operator intent) are not VDA orders (device instructions). `DispatchService` is the only translation point — do not leak VDA sequencing, `orderUpdateId`, or node release into the product API or the UI model.

### REST request → moving forklift

1. `WarehouseController` (thin) → `PlanningService`/`OperationsService` writes order, tasks, load and destination-zone reservations, and the MQTT outbox row **in one transaction**. Creation accepts `Idempotency-Key` via `IdempotencyService`.
2. `DispatchService.dispatchNext()` claims an idle charged AGV, reserves the destination zone (skipping past blocked head-of-queue tasks), builds a `Vda5050.Order` with only the first three nodes released, validates it against the pinned schema, and records the dispatch audit row.
3. `MqttGateway.publishOutbox()` (every 250 ms) drains the outbox to `vda5050/v3/demo/{serial}/order`. Delivery is at-least-once.
4. The simulator executes and publishes `state`, `visualization` (20 Hz), `handling`, and `connection`.
5. `MqttGateway.onState()` validates, then drives `JobExecutionService` transitions; `newBaseRequest` triggers `DispatchService.releaseNext()`, which increments `orderUpdateId` and releases two more nodes.
6. `EventPublisher` publishes domain events to STOMP `/topic/warehouses/linz`; `Main.controller.ts` applies them.

### Two paths, different guarantees

The **command path** is transactional and durable — losing a command is not acceptable, so idempotency comes from stable identities plus conditional transitions (`WarehouseStore.TASK_TRANSITIONS` is the task state machine; every write is a guarded conditional update).

The **telemetry path** is latest-value-wins: `MqttGateway.subscribeLatest` coalesces into a map drained every 50 ms, live poses persist at 2 Hz, and `withLivePose` keeps the slower database row from rewinding the vehicle. Never put 20 Hz poses on the command path.

### Outbound and the robotic cell

After the AGV drops a pallet at `OUT-STG-01`, `RoboticCellService` runs a deterministic WCS-side state machine (`AT_HANDOFF → PICKING → PLACING`) and assigns cartons to the least-loaded `CONV-OUT-01`/`CONV-OUT-02`. This is not VDA — the protocol only covers AGV movement to and from the handoff.

### Persistence and layout

All SQL lives in `WarehouseStore.java` (JdbcTemplate; no ORM). Flyway migrations in `backend/src/main/resources/db/migration` are **forward-only** — never edit an applied one, add the next number. The warehouse topology (racks, nodes, edges, stations, parking, handling poses) is *data in migrations*, not code, so layout changes are migrations; `LayoutValidator` + `LayoutValidatorTest` assert the resulting graph stays routable.

The fleet is deliberately single-vehicle since `V20`. Adding `FL-02` is not a seed row — see the end of `docs/ARCHITECTURE.md` for the parking, charging, and topic-prefix work it requires.

### Frontend shape

- `Component.ts` creates one `JSONModel` named `warehouse` that holds *all* UI state; `Main.controller.ts` is the single controller for `Main.view.xml`.
- Two view modes share that view: **STORY** (default — 3D map plus a narrated line, built by `model/narrative.ts`) and **ENGINEER** (full control tower, order rail, VDA workbench). The choice persists in `localStorage` under `warehouse.viewMode`.
- `control/WarehouseViewport.ts` is a custom UI5 `Control` that owns `visualization/WarehouseScene.ts` (Babylon: racks, forklift with fork kinematics, robot arm via `armKinematics.ts`, conveyors, WASD camera pan).
- `onWarehouseEvent` drops events whose `simulationEpoch` is older than the current one and ignores telemetry for any vehicle id other than the displayed one. `AGV_POSE` feeds the requestAnimationFrame pose buffer directly; other events schedule a snapshot refresh.
- Pure logic lives in `webapp/model/*.ts` and `visualization/armKinematics.ts` so it is unit-testable; DOM/Babylon code is not covered by the QUnit suite.

## When changing things

- Java/domain/VDA change → `.\mvnw.cmd test`. TypeScript/UI change → `npm run typecheck` **and** `npm run build`. REST, MQTT, migration, or Compose change → rebuild images and run the e2e suite against Docker.
- Every emitted standard VDA payload must validate against the pinned schema *before* publication; invalid inbound messages become observable rejections (`AGV_MESSAGE_REJECTED`) and must not mutate domain state.
- Product intent is `docs/PRODUCT.md`, boundaries `docs/ARCHITECTURE.md`, runbook `docs/OPERATIONS.md`, decisions `docs/adr/`. If code and docs disagree, resolve it in the same change.
