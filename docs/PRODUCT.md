# Product brief

## Problem and audience

Warehouse supervisors need one place to understand incoming work, execution progress, vehicle state, and exceptions without reading robot-protocol messages. Integration engineers need the underlying VDA exchange to remain inspectable and auditable. The primary portfolio audience is a senior Technical Product Owner evaluating product framing and system-design judgment.

## Outcomes

- An operator can seed a credible shift, create or cancel a transport order, and explain its task execution in under five minutes.
- An integration engineer can trace a business order to every VDA order update and validation result.
- High-frequency visualization remains smooth without turning PostgreSQL or WebSocket snapshots into a telemetry bottleneck.

## Product measures

For the reference workload, command acknowledgement should remain below 500 ms locally, UI pose updates should remain visually continuous at 20 Hz input, transactional pose persistence is capped at 2 Hz, and all standard VDA output must validate before publication. Operational counters expose rejected messages, outbox retries, coalesced telemetry, and task transitions.

## Scope decisions

Version 0.1.0 proves one compact Linz facility with three simulated forklift AGVs. It includes deterministic scenarios, transport-order prioritization, VDA base/horizon execution, destination-zone reservations, carton-level outbound picking, the `ROBOT-01` two-conveyor cell, instant-action pause/cancel, audit history, and failure injection. Authentication, TLS termination, high availability, and certified conformance remain explicit future work rather than hidden assumptions.

## Evolution path

1. Replace fixed `linz` routing with tenant/warehouse context and access control.
2. Extend the capability registry and assignment policy with battery-aware cost, traffic density, and load compatibility.
3. Expand destination-zone reservations into graph-level traffic arbitration and deadlock recovery.
4. Separate durable command processing from read projections when throughput justifies it.
5. Add production identity, PKI, broker ACLs, retention policies, and disaster recovery.
