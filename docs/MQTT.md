# MQTT contract

All standard topics use prefix `vda5050/v3/demo/FL-01` and validate against the pinned VDA 5050 v3.0.0 schemas.

| Topic | Producer | Consumer | QoS | Retained | Semantics |
|---|---|---|---:|---|---|
| `order` | backend | simulator | 1 | no | At-least-once; deduplicate by `orderId` and `orderUpdateId` |
| `instantActions` | backend | simulator | 1 | no | At-least-once action command with stable `actionId` |
| `state` | simulator | backend | 0 | no | Validated execution projection; transitions are idempotent |
| `visualization` | simulator | backend | 0 | no | Latest-value-wins, intermediate samples may be coalesced |
| `connection` | simulator | backend | 1 | yes | Online/offline lifecycle and resynchronization trigger |

`control` and `handling` are demo-extension topics, not VDA messages. They are isolated from the standard validator and must never be described as VDA conformance features.
