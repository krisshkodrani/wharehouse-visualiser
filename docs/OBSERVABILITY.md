# Observability

Two streams, one vocabulary:

- **Console** keeps the readable Spring pattern, so `docker compose logs -f backend` stays
  scannable while a demo is running.
- **`logs/backend.json` and `logs/simulator.json`** carry the same records as ECS JSON, bind
  mounted from the containers so they can be queried with `jq` straight from the host.

Spring Boot configures the two independently (`logging.structured.format.file`), so neither
compromises for the other. No logging dependency is added; this is built in.

## Why this exists

The backend used to have **three log statements in the whole application**. Two failures cost
hours each, and both left by branches that said nothing at all:

| Failure | What was actually wrong | How it had to be found |
|---|---|---|
| Every queued order stalled | A vehicle held a `task_id` for a task that had already completed, and dispatch claims only vehicles with `task_id is null` | Hand-written SQL against `agv` and `transport_task` |
| A vehicle drove three nodes and stopped | The released base was exhausted and never extended | Reading simulator stdout and matching timestamps by eye |

Both are now a single query. That is the whole point of the field vocabulary below: a reason
buried in a formatted sentence is greppable at best, whereas one in MDC becomes a JSON field
`jq` can select on.

## Fields

Values travel through MDC, so the ECS encoder lifts them into the record rather than baking
them into the message text.

| Field | Meaning |
|---|---|
| `event` | Stable `SCREAMING_SNAKE` name — the primary thing you select on |
| `reason` | Why a branch was taken, when an event has more than one cause |
| `correlationId` | One request, or one browser session, end to end |
| `orderId` | Transport order (operator intent) |
| `taskId` | Transport task (one load movement) |
| `vehicleId` | Vehicle serial, e.g. `FL-01` |
| `loadId` | Pallet or carton |
| `epoch` | Simulation epoch; events from an older epoch are stale by design |
| `source` | `browser` or `simulator` when the record did not originate in the backend |

The backend calls it a transport **task**; the vehicle calls the same thing an **order**. The
simulator writes its order id into `taskId` so one query covers both sides — without that, a
`taskId` search returned backend records only and quietly under-reported the vehicle's half of
the story.

`correlationId` starts in `RequestCorrelationFilter` for HTTP work. It previously stopped at the
servlet boundary, which is why MQTT-side failures were anonymous — `LogContext` now extends the
same key across the MQTT callbacks and the scheduled loops.

## Event catalogue

| Event | Emitted when |
|---|---|
| `DISPATCH_SKIPPED` | Work is queued but cannot start. `reason` is `NO_CLAIMABLE_VEHICLE`, `DESTINATION_ZONE_RESERVED` or `ALL_DESTINATIONS_RESERVED` |
| `TASK_DISPATCHED` | A VDA order was published for a task |
| `BASE_RELEASED` | The released base was extended, with the new `orderUpdateId` |
| `BASE_RELEASE_SKIPPED` | The vehicle asked for more base and got none. `reason` distinguishes a finished base from a lost dispatch |
| `AGV_MESSAGE_REJECTED` | An inbound VDA message failed validation and mutated nothing |
| `OUTBOX_PUBLISHED` / `OUTBOX_PUBLISH_FAILED` / `OUTBOX_STALLED` | Command delivery; the first and last are `DEBUG` |
| `AISLE_DIRECTIVE_APPLIED` | An operator named an aisle and the candidate slots were narrowed |
| `PUTAWAY_PLANNED` / `PUTAWAY_REJECTED` | Planning outcome, with the refusal reason |
| *domain events* | Every event the UI receives, logged once in `EventPublisher` |
| `CARGO_*` | Scene handovers, forwarded from the browser |

An empty dispatch queue is deliberately **not** logged: narrating "nothing to do" would bury the
passes that matter.

### Absence of `DISPATCH_SKIPPED` does not mean healthy

`dispatchNext` is **trigger-driven, not polled** — it runs after planning, after a task
completes, and when the vehicle reports idle. The only `@Scheduled` method in `DispatchService`
is `parkIfIdle`. So if a trigger is missed, queued work waits indefinitely *and no dispatch pass
runs*, which means no `DISPATCH_SKIPPED` line either.

When a queue is stuck, read it as two separate questions:

```bash
# 1. Did a pass even happen?  (no TASK_DISPATCHED and no DISPATCH_SKIPPED for a while = no trigger)
jq -r 'select(.event=="TASK_DISPATCHED" or .event=="DISPATCH_SKIPPED")
       | "\(."@timestamp") \(.event) \(.reason // "")"' logs/backend.json | tail -5

# 2. If passes are happening, why do they decline?
jq 'select(.event=="DISPATCH_SKIPPED") | .reason' logs/backend.json | sort | uniq -c
```

The same shape applies to `BASE_RELEASED`: it is driven by the vehicle's `newBaseRequest`, so a
vehicle that stops asking stops the chain, and silence is the symptom rather than an error.

## Cookbook

```bash
# Why is nothing dispatching?  (the deadlock, in one query)
jq 'select(.event=="DISPATCH_SKIPPED") | {time:."@timestamp", reason, vehicleId}' logs/backend.json

# Why did the vehicle stop mid-route?  (the base/horizon stall)
jq 'select(.event|startswith("BASE_RELEASE")) | {time:."@timestamp", event, reason, taskId}' logs/backend.json

# Follow one task across backend, simulator and browser, in time order.
# -s slurps both line-delimited files into one array so they can be sorted together.
jq -s 'sort_by(."@timestamp") | .[] | select(.taskId=="…")
       | {t:."@timestamp", source:(.source // "backend"), event, message}' logs/*.json

# Everything that touched one pallet, including the scene
jq 'select(.loadId=="IN-001") | {time:."@timestamp", source, event, message}' logs/*.json

# Protocol rejections only
jq 'select(.event=="AGV_MESSAGE_REJECTED")' logs/backend.json

# Anything at WARN or worse
jq 'select(."log.level"=="WARN" or ."log.level"=="ERROR")' logs/*.json
```

## Browser logs

`webapp/diagnostics.js` already buffered every diagnostic and scene event in the page. It now
also forwards them to `POST /api/v1/client-logs`, batched every 10 s, flushed immediately on an
error and on `beforeunload` (with `keepalive`, so an unload flush still leaves the browser). Each
page generates its own `correlationId`, so browser and backend records join up.

The browser sees things the server cannot — a pallet missing from a shelf for five seconds — and
the server sees things the browser cannot — a vehicle holding a stale task. Correlating them used
to mean reading the in-page diagnostics panel and a container log side by side.

A logging failure never surfaces to the operator: the fetch rejection is swallowed, because an
error banner about logs would be worse than the missing logs.

## Metrics

Prometheus metrics remain at `/actuator/prometheus`, reachable from the internal Docker network.
The metric set is still short of the target — see §48 in [the roadmap](ROADMAP.md).

## Limitations

- `POST /api/v1/client-logs` is **unauthenticated**, like the rest of this demo API. It caps
  batches at 200 entries and fields at 512 characters, truncating rather than rejecting, so a
  noisy page costs bounded disk per request — but a determined caller can still write to the log
  file. See [the threat model](THREAT_MODEL.md).
- No rotation policy beyond Logback's default, and no shipping to an external collector.
  OpenTelemetry with OTLP export is roadmap M5.
