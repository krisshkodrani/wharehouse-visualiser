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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.paho.client.mqttv3.MqttMessage;

/** Interprets validated VDA state snapshots as idempotent domain transitions. */
public final class VdaStateHandler {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger("com.example.warehouse.MqttGateway");
  private final ObjectMapper mapper;
  private final VdaMessageValidator validator;
  private final VdaInstantActionService instantActions;
  private final WarehouseStore store;
  private final JobExecutionService execution;
  private final EventPublisher events;
  private final WarehouseMetrics metrics;
  private final ConcurrentHashMap<String, ApiModels.AgvView> liveAgvs;

  public VdaStateHandler(ObjectMapper mapper, VdaMessageValidator validator,
      VdaInstantActionService instantActions, WarehouseStore store, JobExecutionService execution,
      EventPublisher events, WarehouseMetrics metrics,
      ConcurrentHashMap<String, ApiModels.AgvView> liveAgvs) {
    this.mapper = mapper;
    this.validator = validator;
    this.instantActions = instantActions;
    this.store = store;
    this.execution = execution;
    this.events = events;
    this.metrics = metrics;
    this.liveAgvs = liveAgvs;
  }

  public void handle(String topic, MqttMessage message) {
    try {
      String json = new String(message.getPayload(), StandardCharsets.UTF_8);
      validator.validate("state", json);
      Vda5050.State state = mapper.readValue(json, Vda5050.State.class);
      String agvId = serialFromTopic(topic);
      metrics.mqttAccepted(topic);
      if (state.powerSupply() != null)
        store.updatePower(agvId, state.powerSupply().stateOfCharge(), state.powerSupply().charging());
      UUID jobId = uuid(state.orderId());
      if (jobId != null && store.isHousekeepingOrder(state.orderId())) jobId = null;
      if (jobId != null) {
        if (instantActions.consumeFinished(state, "cancelOrder")) {
          execution.cancelled(jobId);
          publishVehicle(agvId);
          return;
        }
        store.acceptDispatch(jobId);
        if (hasAction(state, "pick", "FINISHED")) execution.picked(jobId);
        else if (state.driving()) execution.executing(jobId);
        else if (state.actionStates().isEmpty()
            || state.actionStates().stream().allMatch(action -> "FINISHED".equals(action.get("actionStatus"))))
          execution.completeIfArrived(jobId, state.lastNodeId());
        if (state.newBaseRequest()) execution.releaseNext(jobId);
      }
      publishVehicle(agvId);
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

  private void publishVehicle(String agvId) {
    ApiModels.AgvView agv = withLivePose(store.agv(agvId), liveAgvs.get(agvId));
    liveAgvs.put(agvId, agv);
    events.publish("AGV_UPDATED", agv);
  }

  private static UUID uuid(String value) {
    try {
      return value == null || value.isBlank() ? null : UUID.fromString(value);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private static boolean hasAction(Vda5050.State state, String type, String status) {
    return state.actionStates().stream().anyMatch(action ->
        type.equals(action.get("actionType")) && status.equals(action.get("actionStatus")));
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
