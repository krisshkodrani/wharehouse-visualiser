package com.example.warehouse.vda5050;

import com.example.warehouse.ApiModels;
import com.example.warehouse.transport.JobExecutionService;
import com.example.warehouse.WarehouseStore;
import com.example.warehouse.events.EventPublisher;
import com.example.warehouse.observability.LogContext;
import com.example.warehouse.observability.WarehouseMetrics;
import com.example.warehouse.vda.Vda5050;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.paho.client.mqttv3.MqttMessage;

/** Interprets latest-value VDA visualization telemetry without transactional queuing. */
public final class VdaVisualizationHandler {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger("com.example.warehouse.MqttGateway");
  private static final Set<String> STATIONARY_STATUSES =
      Set.of("PARKING", "CHARGING", "PARKED", "DOCKING");
  private final ObjectMapper mapper;
  private final VdaMessageValidator validator;
  private final WarehouseStore store;
  private final JobExecutionService execution;
  private final EventPublisher events;
  private final WarehouseMetrics metrics;
  private final ConcurrentHashMap<String, ApiModels.AgvView> liveAgvs;

  public VdaVisualizationHandler(ObjectMapper mapper, VdaMessageValidator validator,
      WarehouseStore store, JobExecutionService execution, EventPublisher events,
      WarehouseMetrics metrics, ConcurrentHashMap<String, ApiModels.AgvView> liveAgvs) {
    this.mapper = mapper;
    this.validator = validator;
    this.store = store;
    this.execution = execution;
    this.events = events;
    this.metrics = metrics;
    this.liveAgvs = liveAgvs;
  }

  public void handle(String topic, MqttMessage message) {
    try {
      String json = new String(message.getPayload(), StandardCharsets.UTF_8);
      validator.validate("visualization", json);
      Vda5050.Visualization telemetry = mapper.readValue(json, Vda5050.Visualization.class);
      String agvId = serialFromTopic(topic);
      metrics.mqttAccepted(topic);
      Vda5050.Position position = telemetry.mobileRobotPosition();
      double speed = telemetry.velocity() == null
          ? 0 : Math.hypot(telemetry.velocity().vx(), telemetry.velocity().vy());
      var current = liveAgvs.get(agvId);
      if (current == null) current = store.agv(agvId);
      String status = speed > 0.01 ? "MOVING" : current.status();
      if (speed <= 0.01 && current.taskId() == null && !STATIONARY_STATUSES.contains(current.status()))
        status = "IDLE";
      ApiModels.AgvView updated = new ApiModels.AgvView(
          current.id(), position.x(), position.y(), position.theta(), speed,
          current.battery(), status, current.taskId(), current.charging(),
          current.currentStationId(), current.handlingPhase(), current.forkHeight(),
          current.forkExtension(), current.carriedLoadId());
      liveAgvs.put(agvId, updated);
      events.publish("AGV_POSE", updated);
      if (current.velocity() > 0.01 && speed <= 0.01 && current.taskId() == null)
        execution.agvIdle();
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
