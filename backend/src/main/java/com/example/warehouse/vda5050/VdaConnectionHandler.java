package com.example.warehouse.vda5050;

import com.example.warehouse.events.EventPublisher;
import com.example.warehouse.observability.LogContext;
import com.example.warehouse.observability.WarehouseMetrics;
import com.example.warehouse.vda.Vda5050;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;
import org.eclipse.paho.client.mqttv3.MqttMessage;

/** Interprets validated VDA connection messages and requests runtime synchronization. */
public final class VdaConnectionHandler {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger("com.example.warehouse.MqttGateway");
  private final ObjectMapper mapper;
  private final VdaMessageValidator validator;
  private final EventPublisher events;
  private final WarehouseMetrics metrics;
  private final Consumer<String> online;

  public VdaConnectionHandler(ObjectMapper mapper, VdaMessageValidator validator,
      EventPublisher events, WarehouseMetrics metrics, Consumer<String> online) {
    this.mapper = mapper;
    this.validator = validator;
    this.events = events;
    this.metrics = metrics;
    this.online = online;
  }

  public void handle(String topic, MqttMessage message) {
    try {
      String json = new String(message.getPayload(), StandardCharsets.UTF_8);
      validator.validate("connection", json);
      Vda5050.Connection connection = mapper.readValue(json, Vda5050.Connection.class);
      metrics.mqttAccepted(topic);
      events.publish("AGV_CONNECTION_UPDATED", connection);
      if ("ONLINE".equals(connection.connectionState())) online.accept(serialFromTopic(topic));
    } catch (Exception exception) {
      metrics.mqttRejected(topic);
      try (var scope = LogContext.of(LogContext.EVENT, "AGV_MESSAGE_REJECTED")
          .and(LogContext.REASON, exception.getClass().getSimpleName())
          .and(LogContext.VEHICLE_ID, serialFromTopic(topic)).open()) {
        log.warn("rejected inbound {}: {}", topic, String.valueOf(exception.getMessage()));
      }
      events.publish("AGV_MESSAGE_REJECTED",
          Map.of("topic", topic, "error", String.valueOf(exception.getMessage())));
    }
  }

  private static String serialFromTopic(String topic) {
    String[] parts = topic.split("/");
    return parts.length >= 5 ? parts[3] : Vda5050.SERIAL_NUMBER;
  }
}
