package com.example.warehouse.vda5050;

import com.example.warehouse.ApiModels;
import com.example.warehouse.transport.JobExecutionService;
import com.example.warehouse.WarehouseStore;
import com.example.warehouse.events.EventPublisher;
import com.example.warehouse.observability.LogContext;
import com.example.warehouse.observability.WarehouseMetrics;
import com.example.warehouse.vda.Vda5050;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.paho.client.mqttv3.MqttMessage;

/** Interprets coalesced fork/handling telemetry as vehicle lifecycle state. */
public final class VdaHandlingHandler {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger("com.example.warehouse.MqttGateway");
  private final ObjectMapper mapper;
  private final WarehouseStore store;
  private final JobExecutionService execution;
  private final EventPublisher events;
  private final WarehouseMetrics metrics;
  private final ConcurrentHashMap<String, ApiModels.AgvView> liveAgvs;

  public VdaHandlingHandler(ObjectMapper mapper, WarehouseStore store, JobExecutionService execution,
      EventPublisher events, WarehouseMetrics metrics,
      ConcurrentHashMap<String, ApiModels.AgvView> liveAgvs) {
    this.mapper = mapper;
    this.store = store;
    this.execution = execution;
    this.events = events;
    this.metrics = metrics;
    this.liveAgvs = liveAgvs;
  }

  @SuppressWarnings("unchecked")
  public void handle(String topic, MqttMessage message) {
    try {
      Map<String, Object> value = mapper.readValue(message.getPayload(), Map.class);
      String agvId = serialFromTopic(topic);
      metrics.mqttAccepted(topic);
      String phase = String.valueOf(value.getOrDefault("phase", "IDLE"));
      double forkHeight = ((Number) value.getOrDefault("forkHeight", 0)).doubleValue();
      double forkExtension = ((Number) value.getOrDefault("forkExtension", 0)).doubleValue();
      String loadId = nullable(value.get("loadId"));
      String stationId = nullable(value.get("stationId"));
      store.updateHandling(agvId, phase, forkHeight, forkExtension, loadId, stationId);
      ApiModels.AgvView agv = withLivePose(store.agv(agvId), liveAgvs.get(agvId));
      liveAgvs.put(agvId, agv);
      events.publish("AGV_HANDLING_UPDATED", agv);
      if ("CHARGING".equals(phase) || "PARKED".equals(phase)) execution.agvIdle();
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

  private static String nullable(Object value) {
    if (value == null) return null;
    String text = String.valueOf(value);
    return text.isBlank() || "null".equals(text) ? null : text;
  }

  private static String serialFromTopic(String topic) {
    String[] parts = topic.split("/");
    return parts.length >= 5 ? parts[3] : Vda5050.SERIAL_NUMBER;
  }

  private static ApiModels.AgvView withLivePose(ApiModels.AgvView stored, ApiModels.AgvView live) {
    if (live == null) return stored;
    return new ApiModels.AgvView(stored.id(), live.x(), live.z(), live.theta(), live.velocity(),
        stored.battery(), stored.status(), stored.taskId(), stored.charging(), stored.currentStationId(),
        stored.handlingPhase(), stored.forkHeight(), stored.forkExtension(), stored.carriedLoadId());
  }
}
