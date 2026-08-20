package com.example.warehouse.persistence.outbox;

import com.example.warehouse.mqtt.MqttConnection;
import com.example.warehouse.mqtt.MqttPublisher;
import com.example.warehouse.observability.LogContext;
import com.example.warehouse.observability.WarehouseMetrics;
import java.nio.charset.StandardCharsets;

/** Publishes committed outbox messages with at-least-once delivery semantics. */
public final class OutboxPublisher {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger("com.example.warehouse.MqttGateway");
  private final OutboxService outbox;
  private final MqttConnection connection;
  private final MqttPublisher publisher;
  private final WarehouseMetrics metrics;

  public OutboxPublisher(OutboxService outbox, MqttConnection connection,
      MqttPublisher publisher, WarehouseMetrics metrics) {
    this.outbox = outbox;
    this.connection = connection;
    this.publisher = publisher;
    this.metrics = metrics;
  }

  public void publishPending() {
    if (!connection.isConnected()) {
      if (log.isDebugEnabled()) {
        try (var scope = LogContext.of(LogContext.EVENT, "OUTBOX_STALLED")
            .and(LogContext.REASON, "BROKER_DISCONNECTED").open()) {
          log.debug("outbox not drained: MQTT client is not connected");
        }
      }
      return;
    }
    for (var row : outbox.pending()) {
      try {
        publisher.publish(row.topic(), row.payload().getBytes(StandardCharsets.UTF_8), row.qos(), false);
        outbox.published(row.id());
        metrics.outboxPublished();
        try (var scope = LogContext.of(LogContext.EVENT, "OUTBOX_PUBLISHED").open()) {
          log.debug("published {} (qos {})", row.topic(), row.qos());
        }
      } catch (Exception exception) {
        outbox.failed(row.id());
        metrics.outboxFailed();
        try (var scope = LogContext.of(LogContext.EVENT, "OUTBOX_PUBLISH_FAILED")
            .and(LogContext.REASON, exception.getClass().getSimpleName()).open()) {
          log.warn("could not publish {}: {}", row.topic(), String.valueOf(exception.getMessage()));
        }
        break;
      }
    }
  }
}
