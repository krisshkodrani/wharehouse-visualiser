# Operations

## Signals

Backend health is `/actuator/health`; Prometheus metrics are available internally at `/actuator/prometheus`. Watch `warehouse_mqtt_messages_total`, `warehouse_telemetry_coalesced_total`, `warehouse_outbox_publish_total`, and `warehouse_task_transitions_total` together with JVM, datasource, and HTTP metrics.

## Service objectives for the demo profile

- 99% of local REST commands complete within 500 ms, excluding optional external AI latency.
- No schema-invalid VDA message is published.
- Pending outbox commands drain within five seconds after broker recovery, with vehicle-specific topics for FL-01, FL-02, and FL-03.
- The browser remains responsive while receiving 20 Hz visualization messages.

## Recovery playbook

If MQTT is unavailable, leave PostgreSQL running and restore the broker; pending commands remain in the outbox. If the simulator restarts, each vehicle's ONLINE message causes a vehicle-specific runtime synchronization. If a robot pick remains in `PICKING` or `PLACING` after a controlled restart, reset the scenario; the deterministic cell worker will rebuild carton jobs from the transactional handoff state. If state becomes unsuitable for a demo, use scenario reset rather than editing tables. Database volumes are disposable demo data; a production deployment requires backups, retention, and tested restore procedures.

Never expose the supplied Compose credentials or actuator endpoint to an untrusted network. The published stack is a local reference environment, not a hardened deployment manifest.
