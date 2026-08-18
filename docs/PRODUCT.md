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

Version 0.1.0 proves one compact Linz facility with a single simulated forklift AGV. It includes deterministic scenarios, transport-order prioritization, VDA base/horizon execution, destination-zone reservations, carton-level outbound picking, the `ROBOT-01` two-conveyor cell, instant-action pause/cancel, and audit history. Fault
injection was removed rather than kept: the control fabricated protocol rejections the backend
had never produced, so it demonstrated the banner and not the boundary. Provoking a real
rejection belongs in the simulator and is tracked in the roadmap. Authentication, TLS termination, high availability, and certified conformance remain explicit future work rather than hidden assumptions.

## Directed putaway

Storage is organised into three named aisles, **A**, **B** and **C**, each serving one rack row
and lettered on the warehouse floor so the name an operator says is the name they can see. A
putaway request may name one in its operator prompt ("store this pallet in aisle B"), and the
placement is then constrained to that aisle.

The constraint is applied by narrowing the eligible-slot list before the placement advisor is
consulted, not by instructing it. That matters twice over: the deterministic default provider
does not read the prompt at all, and a constraint the system can check is worth more than one it
merely requests. A named aisle with no free slots is rejected with a stated reason rather than
being quietly satisfied somewhere else, because silently relocating a load is the failure the
operator would not see.

## Evolution path

1. Replace fixed `linz` routing with tenant/warehouse context and access control.
2. Extend the capability registry and assignment policy with battery-aware cost, traffic density, and load compatibility.
3. Expand destination-zone reservations into graph-level traffic arbitration and deadlock recovery.
4. Separate durable command processing from read projections when throughput justifies it.
5. Add production identity, PKI, broker ACLs, retention policies, and disaster recovery.
