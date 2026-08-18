# Architecture

## System context

```mermaid
flowchart LR
  Operator[Warehouse operator] --> UI[OpenUI5 control tower]
  UI -->|REST commands| Backend[Warehouse backend]
  Backend -->|WebSocket events| UI
  Backend -->|VDA orders / instant actions| MQTT[(MQTT broker)]
  MQTT -->|VDA state / visualization / connection| Backend
  MQTT <--> Fleet[FL-01 simulator vehicle]
  Backend --> DB[(PostgreSQL)]
  Backend -->|WCS control| Cell[ROBOT-01 + CONV-OUT-01/02]
  Backend -. optional placement advice .-> AI[OpenRouter]
```

The backend is the transactional system of record. The simulator is deliberately isolated as an external device. MQTT is an integration boundary, not an internal method bus.

## Command and telemetry paths

```mermaid
sequenceDiagram
  participant UI
  participant API
  participant DB
  participant MQTT
  participant AGV
  UI->>API: Create transport order + Idempotency-Key
  API->>DB: Order, tasks, reservation, outbox (one transaction)
  API-->>UI: 202 + normalized order
  DB-->>MQTT: Scheduled outbox publisher
  MQTT->>AGV: VDA order
  AGV-->>MQTT: State / newBaseRequest
  MQTT-->>API: Validated state
  API->>DB: Conditional task transition / next base
  API-->>UI: Domain event
```

Visualization follows a separate latest-value path: MQTT callbacks replace an in-memory pose, the backend emits lightweight pose events, and persistence samples at 2 Hz. Losing an intermediate pose is acceptable; losing a command is not.

Outbound adds a deterministic WCS cell after the AGV task: a pallet arrives at `OUT-STG-01`, carton rows become robot-pick jobs, `ROBOT-01` advances through `AT_HANDOFF → PICKING → PLACING`, and the WCS assigns each carton to the least-loaded `CONV-OUT-01` or `CONV-OUT-02` lane. Conveyor completion ships the carton and closes the business order.

## Ownership and consistency

Transport orders express operator intent; transport tasks are independently schedulable load movements; VDA orders are versioned device instructions. The dispatch audit links these layers without making the product API depend on VDA sequence mechanics. PostgreSQL transactions protect order/task/inventory changes. The outbox makes database commit and eventual MQTT publication observable; delivery is at-least-once, so stable order/update identities and conditional transitions provide idempotency.

## Invariants and failure behavior

- One active task per AGV, one active reservation per load, and one active destination-zone reservation per task.
- The fleet assignment chooses an idle, sufficiently charged vehicle; MQTT topics and VDA serial numbers remain vehicle-specific.
- Priority order is URGENT, HIGH, NORMAL, then creation/sequence order.
- An accepted VDA update never decreases `orderUpdateId`; stale epochs are ignored.
- Invalid VDA input becomes an observable rejection and cannot mutate domain state.
- Broker loss leaves commands pending; reconnect resumes outbox publication and synchronizes runtime state.
- AI advice is optional. Provider failure creates no tasks and changes no inventory.

## Capacity and trade-offs

The reference workload is one forklift AGV, carton-level outbound work, and 20 Hz visualization. A single PostgreSQL writer, destination-zone reservations, and a scheduled outbox are intentionally sufficient for this compact facility. Partitioned telemetry ingestion and horizontal command workers remain future scaling options rather than implicit assumptions.

The fleet is deliberately single-vehicle (`V20`). An earlier revision seeded `FL-02` and `FL-03` and made them claimable for transport tasks, but the parking and charging lifecycle stayed bound to `FL-01`, so the companions could never dock and therefore never recharge — the fleet decayed back to one vehicle as their batteries fell below the claim threshold. Adding a second vehicle is a coherent change, not a configuration flip: it requires threading `agvId` through `WarehouseStore.parkingTargets`, `WarehouseStore.enqueueParking`, and `DispatchService.parkIfIdle`, publishing to `Vda5050.topicPrefix(agvId)` instead of a literal topic, and giving each vehicle a reachable charging bay after reset.
