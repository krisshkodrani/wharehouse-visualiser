# Warehouse Control Tower

An OpenUI5 and Babylon.js warehouse control tower backed by Spring Boot, PostgreSQL, MQTT, and a simulated autonomous forklift. Business-facing transport orders are decomposed into load-movement tasks and dispatched as schema-validated VDA 5050 v3.0.0 orders.

> Reference implementation for product and system-design discussion. It is not VDA-certified, a functional-safety component, or a production deployment.

[Architecture at a glance](https://claude.ai/code/artifact/1a491dad-2323-4427-b87d-d707841c09f6) · [Architecture](docs/ARCHITECTURE.md) · [Roadmap](docs/ROADMAP.md) · [Product brief](docs/PRODUCT.md) · [OpenAPI](docs/openapi.yaml) · [MQTT contract](docs/MQTT.md) · [Operations](docs/OPERATIONS.md) · [Threat model](docs/THREAT_MODEL.md) · [Contributing](CONTRIBUTING.md)

![Story view: the live warehouse with the current order narrated beneath it](artifacts/warehouse-story-view.png)

The app opens in **story view**: the 3D warehouse, one line describing what the vehicle is
doing right now, and a strip showing where the current order sits between operator intent and
completed execution. **Engineer view** is one click away in the header and restores the full
control tower — transport order rail, execution timeline, VDA 5050 protocol workbench, and
operational KPIs. The `ⓘ` button explains the six steps between a REST request and a moving
forklift.

## Run locally with Docker

Requirements: Docker Desktop with Compose v2.

```powershell
Copy-Item .env.example .env
docker compose up --build --wait
```

Open <http://localhost:8080>. The default `AI_PROVIDER=mock` is deterministic and requires no external service.

On first launch, choose one of three deterministic operational stories:

- **Balanced shift**: concurrent inbound and outbound demand.
- **Inbound surge**: a high-priority six-load put-away order.
- **Outbound wave**: an urgent six-load shipment.

The selected scenario starts automatically. Reload preserves it; **Reset scenario** returns to the chooser.

To use OpenRouter, edit `.env`:

```dotenv
AI_PROVIDER=openrouter
OPENROUTER_API_KEY=your-key
OPENROUTER_MODEL=openai/gpt-4o-mini
OPENROUTER_PROVIDER=groq
```

OpenRouter failures are shown to the operator and do not create jobs or change inventory. The key is used only by the backend.

Reset the demo by removing its local volumes:

```powershell
docker compose down -v
```

This deletes the demo database and broker data.

## Development

The frontend remains a standard UI5 project:

```powershell
npm ci
npm run typecheck
npm run build
npm test
```

The repository includes a self-bootstrapping Maven Wrapper, so no global Maven installation is required:

```powershell
.\mvnw.cmd test
```

Java 21 is required for host-side Java development. PostgreSQL and Mosquitto can be started independently with:

```powershell
docker compose up -d --wait postgres mosquitto
```

## Service boundaries

- `backend/`: authoritative inventory, transport orders/tasks, scenario seeding, A* routing, REST, WebSocket deltas, MQTT outbox, and VDA dispatch audit trail.
- `simulator/`: independent AGV process; consumes orders and instant actions, then publishes VDA state, visualization, and connection messages over MQTT.
- `vda5050-contracts/`: shared message records and the official VDA 5050 v3.0.0 JSON schemas.
- `webapp/`: OpenUI5 control-tower UI and Babylon visualization with interpolated live vehicle movement.

REST starts at `/api/v1` and WebSocket/STOMP at `/ws`. MQTT topics use the prefix `vda5050/v3/demo/FL-01/`. Five are standard VDA 5050 and every one is validated against the pinned v3.0.0 schemas before publication or acceptance — `order` and `instantActions` outbound, `state`, `visualization`, and `connection` inbound. Two more are project extensions carried on the same broker: `control` (simulation epoch, time scale, and reset baseline) and `handling` (fork kinematics).

Transport-order creation accepts an optional `Idempotency-Key`; reuse with the same body returns the existing order and conflicting reuse returns RFC 9457 Problem Details. Operational metrics are available to the internal backend network at `/actuator/prometheus`.

## Interview demo path

1. Select **Inbound surge** and point out the automatically created business order and six transport tasks.
2. Stay in story view: the narrated line and the stage strip carry the flow from operator
   intent to completed execution without any explanation of the screen.
3. Orbit and pan the 3D map — drag to orbit, **WASD** to pan, scroll to zoom — while the
   vehicle drives itself from live telemetry.
4. Open **ⓘ How this works** for the six steps between the REST request and a moving forklift.
5. Switch to **Engineer view** and open the **VDA 5050 workbench** to show schema validity,
   the stable order ID, update IDs, base/horizon release, and the raw payload.
6. Pause and resume the fleet to demonstrate standard `startPause` and `stopPause` instant actions.

## Deliberate demo limits

The public data model and UI support nullable AGV assignment and multiple vehicles, but the demo simulates only FL-01. Authentication, TLS, and multi-AGV traffic arbitration remain outside the interview scope. Simulation speed and scenario reset are explicitly demo controls rather than VDA messages. There is no fault-injection control: rejection and blocked-route recovery are real backend behaviours, and a button that fabricated them would not demonstrate the same thing. Driving them from the simulator is tracked in the [roadmap](docs/ROADMAP.md).

## License

Apache License 2.0. See [third-party notices](THIRD_PARTY_NOTICES.md) for VDA 5050, OpenUI5, Babylon.js, and other component attribution.
