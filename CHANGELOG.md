# Changelog

All notable changes are documented here. The project follows Semantic Versioning and Conventional Commits.

## Unreleased

## 0.2.0 - 2026-08-18

### Added

- Public deployment topology (`compose.prod.yaml`): images pulled from GHCR by pinned tag,
  Caddy terminating TLS as the only publicly bound service, per-service memory limits and
  JVM heap caps sized for a 2 GB host, and restart policies so the stack survives a reboot.
- Rate limiting on the two routes that reach the LLM (`docker/nginx.prod.conf`), keyed so
  that only POSTs are counted and read traffic passes free.
- `.env.production.example` documenting the deployment variables, with required values
  guarded by `${VAR:?}` so a missing secret fails the deploy rather than a later request.
- Story view as the default screen: the 3D warehouse fills the viewport, with a narrated
  "now" line, a six-stage Order to Done pipeline strip, and a VDA protocol proof line.
  Engineer view keeps the full control tower one click away and the choice persists.
- "How this works" overlay tracing the six steps from REST request to moving forklift.
- Durable execution timeline events (`V22`) carrying order, task, vehicle, correlation,
  VDA order id, and update id.
- Layout validator asserting the warehouse graph stays routable, run as part of the test suite.
- WASD now pans the 3D camera; drag orbits and scroll zooms.

### Fixed

- The forklift never moved: `MqttGateway` read the message type instead of the vehicle serial
  from inbound VDA topics, so every telemetry handler looked up a vehicle named "state" and
  threw. Poses, battery, and handling phases never reached the database or the browser.
- Rejection events no longer risk a null-value failure when an exception carries no message.
- Outbound routing restored after the robotic cell was introduced, and the robot cell and
  conveyors are modelled separately (`V17`-`V21`).

### Removed

- `POST /demo/events` fault injection. It fabricated protocol rejections and blocked routes
  that the backend had never produced, demonstrating the UI and nothing else. Real injection
  belongs in the simulator and is tracked in the roadmap.
- The second and third simulated vehicles. They shared one handoff and deadlocked; the fleet
  is deliberately single-vehicle since `V20`.

## 0.1.0 - 2026-08-15

### Added

- Scenario-led warehouse control tower with deterministic shift presets.
- Normalized transport orders and tasks with VDA 5050 v3 dispatch audit.
- Base/horizon updates, instant actions, cancellation, and smooth AGV simulation.
- Idempotent command boundary, Problem Details, and operational metrics.

### Known limitations

- One warehouse and one simulated AGV.
- Local demo credentials with no authentication or TLS.
- Reference implementation only; not VDA-certified or suitable for physical equipment.
