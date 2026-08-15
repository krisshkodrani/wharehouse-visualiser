package com.example.warehouse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
class WarehouseMetrics {
  private final MeterRegistry registry;

  WarehouseMetrics(MeterRegistry registry) { this.registry = registry; }

  void mqttAccepted(String topic) { counter("warehouse_mqtt_messages_total", "accepted", topic).increment(); }
  void mqttRejected(String topic) { counter("warehouse_mqtt_messages_total", "rejected", topic).increment(); }
  void telemetryCoalesced(String topic) { counter("warehouse_telemetry_coalesced_total", "replaced", topic).increment(); }
  void outboxPublished() { registry.counter("warehouse_outbox_publish_total", "result", "published").increment(); }
  void outboxFailed() { registry.counter("warehouse_outbox_publish_total", "result", "failed").increment(); }
  void taskTransition(String status) { registry.counter("warehouse_task_transitions_total", "status", status).increment(); }

  private Counter counter(String name, String outcome, String topic) {
    return registry.counter(name, "outcome", outcome, "topic", topic.substring(topic.lastIndexOf('/') + 1));
  }
}
