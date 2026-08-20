# Refactoring Plan

This is a structured refactor plan intended to align code organization with the existing architecture, with **no feature changes** and no protocol/UI/data-model behavior changes.

## Core rule

- No feature work during refactor.
- No protocol changes.
- No UI redesign.
- No database redesign unless required for code-organization.

The architecture is already structurally correct: backend is authoritative, MQTT is an external integration boundary, the simulator behaves as an external AGV, and OpenUI5/Babylon is the presentation layer.

## Target package structure

```text
wharehouse-visualiser/
│
├── webapp/
│   ├── view/
│   │   ├── Main.view.xml
│   │   ├── fragments/
│   │   │   ├── Header.fragment.xml
│   │   │   ├── OrderRail.fragment.xml
│   │   │   ├── WarehouseStage.fragment.xml
│   │   │   ├── KpiOverlay.fragment.xml
│   │   │   ├── ExecutionPanel.fragment.xml
│   │   │   ├── StoryBar.fragment.xml
│   │   │   ├── execution/
│   │   │   │   ├── OrderSummary.fragment.xml
│   │   │   │   ├── TaskJourney.fragment.xml
│   │   │   │   ├── ProtocolHealth.fragment.xml
│   │   │   │   └── ActivityFeed.fragment.xml
│   │   │   └── dialogs/
│   │   │       ├── TransportOrderDialog.fragment.xml
│   │   │       ├── ScenarioDialog.fragment.xml
│   │   │       ├── VdaInspectorDialog.fragment.xml
│   │   │       └── HowItWorksDialog.fragment.xml
│   │
│   ├── controller/
│   │   └── Main.controller.ts
│   ├── service/
│   │   ├── WarehouseApi.ts
│   │   ├── WarehouseEventService.ts
│   │   ├── TransportOrderService.ts
│   │   └── ScenarioService.ts
│   ├── model/
│   │   ├── types.ts
│   │   ├── selectors.ts
│   │   ├── presentation.ts
│   │   └── formatters.ts
│   ├── control/
│   │   └── WarehouseViewport.ts
│   └── visualization/
│       ├── WarehouseScene.ts
│       ├── entities/
│       │   ├── ForkliftVisual.ts
│       │   ├── RackVisual.ts
│       │   ├── PalletVisual.ts
│       │   ├── ConveyorVisual.ts
│       │   ├── RobotCellVisual.ts
│       │   ├── ChargingStationVisual.ts
│       │   └── WarehouseStructureVisual.ts
│       ├── factories/
│       │   ├── MaterialFactory.ts
│       │   └── CargoFactory.ts
│       ├── animation/
│       │   ├── ForkliftAnimator.ts
│       │   ├── CargoHandoverAnimator.ts
│       │   └── RobotArmAnimator.ts
│       ├── telemetry/
│       │   ├── PoseInterpolator.ts
│       │   └── ForkInterpolator.ts
│       └── armKinematics.ts
│
├── backend/
│   └── src/main/java/com/example/warehouse/
│       ├── api/
│       ├── transport/
│       ├── fleet/
│       ├── inventory/
│       ├── routing/
│       ├── vda5050/
│       ├── mqtt/
│       ├── events/
│       ├── persistence/
│       ├── scenario/
│       ├── idempotency/
│       ├── observability/
│       └── config/
│
├── simulator/
│   └── ...
└── vda5050-contracts/
```

## Phase 0 — safety net (prerequisite)

Run and keep as mandatory gates before refactor moves:

- `npm run typecheck`
- `npm run build`
- `npm test`
- `npm run test:e2e:package`
- `.\mvnw.cmd test`

For each meaningful extraction:

1. `typecheck`
2. frontend unit tests
3. frontend build
4. backend tests (if backend touched)
5. `test:e2e` package lifecycle

Preserve demo regression scenarios:

- Balanced shift
- Inbound surge
- Outbound wave

Freeze behavior for:

- PUTAWAY completes
- OUTBOUND completes
- cargo does not duplicate
- cargo remains visually persistent during handover
- forklift pose remains smooth
- fork animation remains synchronized
- robot picks cartons
- conveyors complete shipment
- pause/resume works
- scenario reset works
- VDA inspector still receives dispatch history

## Phase 1 — OpenUI5 view decomposition

Split `Main.view.xml` into:

- `Header.fragment.xml`
- `OrderRail.fragment.xml`
- `WarehouseStage.fragment.xml`
- `ExecutionPanel.fragment.xml`
- `StoryBar.fragment.xml`

Then split `ExecutionPanel` into:

- `execution/OrderSummary.fragment.xml`
- `execution/TaskJourney.fragment.xml`
- `execution/ProtocolHealth.fragment.xml`
- `execution/ActivityFeed.fragment.xml`

Keep `Main.controller.ts` as the only controller and preserve all bindings/handlers exactly.

Completion target: `Main.view.xml` falls from ~33 KB to a few KB.

## Phase 2 — dialogs out of main view/controller

Move dialog XML to:

- `view/fragments/dialogs/TransportOrderDialog.fragment.xml`
- `view/fragments/dialogs/ScenarioDialog.fragment.xml`
- `view/fragments/dialogs/VdaInspectorDialog.fragment.xml`
- `view/fragments/dialogs/HowItWorksDialog.fragment.xml`

Keep lifecycle in `Main.controller.ts`; add `DialogManager.ts` only if needed.

## Phase 3 — simplify `Main.controller.ts`

Keep controller responsibilities at: user action → service call → model update/navigation.

Create:

- `service/WarehouseApi.ts` for imperative API calls:
  - `getSnapshot`
  - `createTransportOrder`
  - `cancelTransportOrder`
  - `receiveInventory`
  - `resetScenario`
  - `setSimulationSpeed`
  - `pauseFleet`
  - `resumeFleet`
- `service/WarehouseEventService.ts` for websocket/stomp lifecycle:
  - connect/reconnect
  - subscribe/parse events
  - connection state

Controller should consume parsed events, not websocket internals.

## Phase 4 — frontend derived state

Introduce:

- `model/selectors.ts`
- `model/presentation.ts`

Move presentation shaping from controller into pure functions, for example:

- `selectSelectedOrder(...)`
- `selectAttentionOrders(...)`
- `calculateOrderProgress(...)`
- `buildInspectionModel(...)`
- `buildActivityFeed(...)`
- `buildNarrativeState(...)`

Controller should mostly set these derived payloads onto the model.

## Phase 5 — Babylon materials

Extract `MaterialFactory.ts` and centralize caching:

- `floor()`, `steel()`, `cardboard()`, `pallet()`, `rack()`, `forkliftBody()`, `warning()`, `charging()`

Start with this low-risk extraction from large `WarehouseScene.ts`.

## Phase 6 — static Babylon entities

Extract static visuals with narrow APIs:

- `WarehouseStructureVisual.ts`
- `ChargingStationVisual.ts`
- `RackVisual.ts`

Each visual owns its own meshes and exposes minimal operations and `dispose`.

## Phase 7 — cargo abstraction

Create:

- `PalletVisual.ts`
- `CargoFactory.ts`

Preserve load identity continuity:

- logical load should move across shelf → forklift → staging as the same visual object.

Keep existing pending/orphan handling and handover grace behavior intact.

## Phase 8 — forklift visual extraction

Create `ForkliftVisual.ts` with semantic API:

- `setPose(...)`
- `setForkHeight(...)`
- `setForkExtension(...)`
- `attachCargo(...)`
- `detachCargo(...)`
- `setCharging(...)`

`WarehouseScene` should not manipulate fork internals directly.

## Phase 9 — interpolation extraction

Create:

- `telemetry/PoseInterpolator.ts`
- `telemetry/ForkInterpolator.ts`

Both should be small, testable state machines:

- `push(sample)`
- `sample(renderTime)`

Keep delay-based smoothing semantics unchanged.

## Phase 10 — forklift animation / visual state

Add `animation/ForkliftAnimator.ts` for temporal behavior:

- wheel rotation
- movement state
- fork state coordination
- load/unload sequencing

Keep interpolation separate from animation and visual representation.

## Phase 11 — cargo handover animation

Create `animation/CargoHandoverAnimator.ts` for visual transitions only:

- rack → fork
- fork → rack
- receiving → fork
- fork → outbound staging
- handoff → robot
- robot → conveyor

Business decisions remain backend-authored; animator only displays state transitions.

## Phase 12 — robot cell

Create:

- `RobotCellVisual.ts`
- `RobotArmAnimator.ts`
- keep `armKinematics.ts` purely mathematical

Robot geometry and IK-driven animation should be cleanly separated.

## Phase 13 — conveyors

Create `ConveyorVisual.ts` per logical conveyor owning:

- belt
- rollers
- frame
- infeed/outfeed/cargo positions

Avoid embedding business state transitions in visuals.

## Phase 14 — make `WarehouseScene` orchestrator

After extraction, reduce scene to assembly and orchestration responsibilities:

- `sync/config/build`
- `setAgvState`
- `setHandlingTelemetry`
- `syncInventory`
- `updateFrame`

Scene coordinates `forkliftAnimator`, `cargoAnimator`, and `robotAnimator`.

## Phase 15 — frontend naming & contracts

Avoid generic class names (`Utils`, `Helpers`, `Manager`, `Common`, `BaseService`).

Prefer domain intent:

- `CargoHandoverAnimator`
- `TransportOrderService`
- `PoseInterpolator`
- `VdaInspectorModel`
- `VehicleAssignmentPolicy`

## Phase 16 — backend package refactor (after frontend stabilizes)

Move classes from flat package into:

- `api/`
- `transport/`
- `fleet/`
- `inventory/`
- `routing/`
- `mqtt/`
- `vda5050/`
- `scenario/`
- `events/`
- `persistence/`
- `observability/`

Behavior should remain unchanged.

## Phase 17 — API separation

Split `ApiModels.java` into explicit DTOs under `api/dto/`:

- `WarehouseSnapshotResponse`
- `AgvResponse`
- `TransportOrderResponse`
- `TransportTaskResponse`
- `CreateTransportOrderRequest`
- `RuntimeResponse`
- `ScenarioResponse`

Preserve boundaries:

- persistence model
- domain model
- API DTO

## Phase 18 — MQTT split

Split `MqttGateway` into:

- `MqttConnection.java`
- `MqttPublisher.java`
- `MqttSubscriber.java`
- `TopicFactory.java`

MQTT layer handles transport concerns only (broker/connection/QoS/topics/delivery), not domain semantics.

## Phase 19 — VDA protocol boundary

Add VDA boundary services:

- `VdaMessageValidator`
- `VdaOrderFactory`
- `VdaStateHandler`
- `VdaVisualizationHandler`
- `VdaConnectionHandler`
- `VdaInstantActionService`

Flow:

`MQTT message -> VDA schema validation -> VDA interpretation -> domain action`

Keep `vda5050-contracts` as schema source only.

## Phase 20 — DispatchService decomposition

Keep `DispatchService` orchestration and extract:

- `fleet/VehicleAssignmentPolicy.java`
- `fleet/ChargingPolicy.java`
- `routing/RoutePlanner.java`
- `routing/ReservationService.java`
- `vda5050/VdaOrderFactory.java`

Dispatch should read like:

- next task
- choose vehicle
- reserve destination
- calculate route
- create VDA order
- write outbox

## Phase 21 — routing boundary

Extract routing components:

- `RoutePlanner`
- `Graph`
- `AStarRoutePlanner`
- `ReservationService`
- `LayoutValidator`

Keep APIs domain-neutral (positions + routing constraints).

## Phase 22 — fleet domain

Introduce:

- `fleet/VehicleAssignmentPolicy`
- `fleet/ChargingPolicy`
- `fleet/ParkingService`

Use stable signatures suitable for one AGV now and multiple later, e.g. `Optional<Agv> selectVehicle(Task task)`.

## Phase 23 — inventory/placement

Introduce:

- `inventory/PlacementService`
- `inventory/PlacementAdvisor`
- `inventory/AisleDirective`
- `inventory/InventoryService`

Keep deterministic constraints separate from advisory logic.

## Phase 24 — outbox boundary

Make outbox structure explicit:

- `persistence/outbox/OutboxMessage`
- `persistence/outbox/OutboxRepository`
- `persistence/outbox/OutboxService`
- `persistence/outbox/OutboxPublisher`

Maintain current transaction order:

- business state + reservation + outbox record written together
- commit
- asynchronous outbox publish

## Phase 25 — WebSocket/domain events

Separate domain events from UI models:

- domain/application event
- EventPublisher
- WebSocket publisher
- OpenUI5 subscribers

Frontend derives its own presentation models.

## Phase 26 — scenario/demo isolation

Move demo logic to:

- `scenario/ScenarioService`
- `scenario/ScenarioSeeder`
- `scenario/ScenarioPreset`

Keep controls (Balanced shift / Inbound surge / Outbound wave / reset / simulation speed) as demo concerns.

## Phase 27 — simulator organization

Refactor simulator to simulate AGV behavior, not backend coupling:

- `mqtt/SimulatorMqttClient`
- `mqtt/TopicSubscriptions`
- `vda5050/OrderHandler`
- `vda5050/InstantActionHandler`
- `vda5050/StatePublisher`
- `vda5050/VisualizationPublisher`
- `vda5050/ConnectionPublisher`
- `vehicle/VehicleState`
- `vehicle/MotionController`
- `vehicle/ForkController`
- `vehicle/BatteryModel`
- `execution/OrderExecutor`
- `execution/NodeExecutor`
- `execution/ActionExecutor`
- `runtime/SimulationClock`
- `runtime/SimulationControl`

## Phase 28 — simulator realism separation

Separate:

- logical state
- physical simulation
- protocol representation

Use clear conversion boundaries between:

- `VehicleState`
- `MotionController`
- `StatePublisher`
- `VisualizationPublisher`

This enables simulator-driven failure behavior at protocol boundaries.

## Phase 29 — configuration cleanup

Centralize configuration per layer:

- Frontend: API/WebSocket URLs, telemetry tuning, render delay
- Backend: MQTT, scheduler, telemetry persistence frequency, routing thresholds, battery thresholds, outbox timing
- Simulator: vehicle identity, speeds, fork speed, battery drain/charge, telemetry frequency

Prefer removing magic numbers from operational logic.

## Phase 30 — observability cleanup

Standardize correlation identifiers end-to-end:

- `transportOrderId`
- `transportTaskId`
- `vehicleId`
- `vdaOrderId`
- `orderUpdateId`
- `correlationId`
- `simulationEpoch`

Flow should remain consistently traceable:

`REST -> transport order -> task -> VDA dispatch -> MQTT -> vehicle state -> domain event -> WebSocket`

## Explicitly out of scope during this refactor

- microservices
- Kafka/Kubernetes
- CQRS framework
- event sourcing
- generic repository abstractions
- generic service base classes
- dependency-injection wrappers everywhere
- full aggregate-first DDD for every table

Single-writer PostgreSQL + scheduled outbox remains the intended production baseline.

## Suggested execution order

1. Freeze tests and baseline scenarios.
2. Extract UI5 fragments.
3. Extract dialogs.
4. Extract frontend services.
5. Extract selectors/presentation functions.
6. Extract Babylon `MaterialFactory`.
7. Extract static visuals.
8. Extract racks.
9. Extract pallet/cargo visuals.
10. Extract forklift visual.
11. Extract pose/fork interpolation.
12. Extract forklift animator.
13. Extract cargo handover animator.
14. Extract robot visual and animator.
15. Extract conveyors.
16. Reduce `WarehouseScene` to orchestration.
17. Backend package reorganization.
18. Split API DTOs.
19. Split MQTT transport.
20. Add VDA handling layer.
21. Decompose dispatch policies.
22. Formalize routing/reservations.
23. Formalize fleet/charging/parking.
24. Formalize inventory/placement.
25. Isolate outbox implementation.
26. Domain/WebSocket event cleanup.
27. Isolate scenario/demo functions.
28. Refactor simulator boundaries.
29. Consolidate configuration.
30. Improve observability and docs.

## Definition of done

A new developer should be able to infer architecture from structure:

- OpenUI5: panels, services, Babylon visualization
- Babylon: entities, animation, telemetry
- Backend: transport, inventory, fleet, routing, VDA, MQTT
- Simulator: vehicle, execution, VDA/MQTT

User-facing behavior should remain effectively identical after completion.
