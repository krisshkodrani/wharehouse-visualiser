# Operations

## Signals

Backend health is `/actuator/health`; Prometheus metrics are available internally at `/actuator/prometheus`. Watch `warehouse_mqtt_messages_total`, `warehouse_telemetry_coalesced_total`, `warehouse_outbox_publish_total`, and `warehouse_task_transitions_total` together with JVM, datasource, and HTTP metrics.

## Service objectives for the demo profile

- 99% of local REST commands complete within 500 ms, excluding optional external AI latency.
- No schema-invalid VDA message is published.
- Pending outbox commands drain within five seconds after broker recovery, on the vehicle-specific topic for FL-01.
- The browser remains responsive while receiving 20 Hz visualization messages.

## Recovery playbook

If MQTT is unavailable, leave PostgreSQL running and restore the broker; pending commands remain in the outbox. If the simulator restarts, each vehicle's ONLINE message causes a vehicle-specific runtime synchronization. If a robot pick remains in `PICKING` or `PLACING` after a controlled restart, reset the scenario; the deterministic cell worker will rebuild carton jobs from the transactional handoff state. If state becomes unsuitable for a demo, use scenario reset rather than editing tables. Database volumes are disposable demo data; a production deployment requires backups, retention, and tested restore procedures.

Never expose the supplied Compose credentials or actuator endpoint to an untrusted network. `compose.yaml` is a local reference environment, not a hardened deployment manifest; the public deployment uses `compose.prod.yaml`, which replaces the default credentials, publishes only Caddy, and keeps the actuator unroutable.

## Public deployment

The demo runs at <https://whv.aipoweredapps.dev> on a single AWS Lightsail instance (`small_3_0`, 2 GB, eu-central-1), described by `compose.prod.yaml`. Nothing is built on the instance: `.github/workflows/release.yml` publishes multi-arch images to GHCR on a `v*` tag, and the instance pulls them. Caddy terminates TLS with an automatically renewed Let's Encrypt certificate and is the only service bound to a public interface; nginx keeps `/api/` and `/ws` proxying and adds rate limiting, and `/actuator` is proxied by neither, so metrics stay internal.

Four files live on the instance in `/opt/whv/`: `compose.prod.yaml`, `docker/Caddyfile`, `docker/nginx.prod.conf`, and `docker/mosquitto.conf`. The repository is not cloned there.

### Deploy and roll back

Both directions are the same edit, because images are pinned by tag and the tag lives in `.env`:

```bash
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=v0.1.1/' /opt/whv/.env   # or back to the previous tag
docker compose -f compose.prod.yaml pull
docker compose -f compose.prod.yaml up -d
```

Rollback covers application code, not schema. Flyway migrations are forward-only, so returning to an image older than an applied migration is safe only when that migration was additive. The demo database is disposable: reset the scenario, or wipe the volume and re-seed.

### Secrets

`/opt/whv/.env` is `chmod 600` and never committed. `POSTGRES_PASSWORD` and `MQTT_PASSWORD` are generated on the instance and exist nowhere else; only `OPENROUTER_API_KEY` travels. Required variables use `${VAR:?}` so a missing value fails `docker compose up` rather than surfacing later — `PlacementAdvisor` otherwise rejects a blank key only at request time.

Two constraints worth remembering. `POSTGRES_PASSWORD` is read by the image only when the data directory is empty, so it cannot be rotated without wiping the volume. `MQTT_PASSWORD` is written into the broker's own password file by the `mosquitto` service command and used by the backend and simulator, so all three come from that one variable and cannot drift apart.

A Lightsail snapshot captures `.env`; never share one. To rotate the API key, revoke it at OpenRouter, edit `.env`, and run `up -d`.

### Cost control

`AI_PROVIDER=openrouter` means real spend, and the endpoint is deliberately unauthenticated. Only two routes reach the model — `POST /api/v1/warehouses/linz/putaway-requests` and `POST /api/v1/warehouses/linz/transport-orders` — and nginx limits each source address to roughly six per minute. Choosing or resetting a scenario calls no model at all, so the headline demo flow is free. The account-level spending cap at OpenRouter is the only control that cannot be bypassed from outside the instance. Setting `AI_PROVIDER=mock` and restarting the backend takes spend to zero without changing anything else.

### Capacity

The workload never idles: telemetry drains every 50 ms, the outbox publishes every 250 ms, the robotic cell ticks every 250 ms, and the simulator emits visualization at 20 Hz. The `small_3_0` bundle sustains 20% CPU per vCPU before burst credits drain, so the Lightsail alarm to watch is **burst capacity**, not average CPU. Memory is the other limit: `mem_limit` and `JAVA_TOOL_OPTIONS` in `compose.prod.yaml` exist because two JVMs would otherwise each claim a quarter of host RAM. Sustained pressure on either is the signal to move to `medium_3_0`, not to tune further.
