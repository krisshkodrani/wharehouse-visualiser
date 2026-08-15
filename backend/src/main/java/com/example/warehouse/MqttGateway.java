package com.example.warehouse;

import com.example.warehouse.vda.Vda5050;
import com.example.warehouse.vda.VdaSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class MqttGateway {
  private final ObjectMapper mapper;
  private final WarehouseStore store;
  private final JobExecutionService execution;
  private final EventPublisher events;
  private final WarehouseMetrics metrics;
  private final VdaSchemaValidator validator;
  private final MqttClient client;
  private final MqttConnectOptions options;
  private final ExecutorService mqttCallbacks = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("backend-mqtt-callback").factory());
  private final AtomicReference<InboundMessage> latestVisualization = new AtomicReference<>();
  private final AtomicReference<InboundMessage> latestHandling = new AtomicReference<>();
  private final AtomicReference<ApiModels.AgvView> liveAgv = new AtomicReference<>();
  private final AtomicLong instantActionHeader = new AtomicLong();

  private record InboundMessage(String topic, MqttMessage message) {}

  MqttGateway(ObjectMapper mapper, WarehouseStore store, JobExecutionService execution, EventPublisher events, WarehouseMetrics metrics,
      @Value("${warehouse.mqtt-url}") String url,
      @Value("${warehouse.mqtt-user}") String user,
      @Value("${warehouse.mqtt-password}") String password) throws Exception {
    this.mapper = mapper; this.store = store; this.execution = execution; this.events = events; this.metrics = metrics;
    this.validator = new VdaSchemaValidator(mapper);
    this.client = new MqttClient(url, "warehouse-backend", new MemoryPersistence());
    this.options = new MqttConnectOptions();
    options.setAutomaticReconnect(true);
    options.setCleanSession(true);
    options.setUserName(user);
    options.setPassword(password.toCharArray());
    options.setConnectionTimeout(5);
  }

  @Scheduled(fixedDelay = 5000, initialDelay = 1000)
  void ensureConnected() {
    if (client.isConnected()) return;
    try {
      client.connect(options);
      // Control messages are commands, not durable state. Remove any retained
      // command left by an older deployment before the simulator subscribes.
      client.publish(Vda5050.TOPIC_PREFIX + "/control", new byte[0], 1, true);
      subscribe(Vda5050.TOPIC_PREFIX + "/state", this::onState);
      subscribeLatest(Vda5050.TOPIC_PREFIX + "/visualization", latestVisualization);
      subscribe(Vda5050.TOPIC_PREFIX + "/connection", this::onConnection);
      subscribeLatest(Vda5050.TOPIC_PREFIX + "/handling", latestHandling);
    } catch (Exception ignored) {
      // Health remains degraded through broker connectivity; retry on the next scheduled tick.
    }
  }

  @Scheduled(fixedDelay = 250, initialDelay = 2500)
  void publishOutbox() {
    if (!client.isConnected()) return;
    for (WarehouseStore.OutboxRow row : store.pendingOutbox()) {
      try {
        client.publish(row.topic(), row.payload().getBytes(StandardCharsets.UTF_8), row.qos(), false);
        store.sent(row.id());
        metrics.outboxPublished();
      } catch (Exception exception) {
        store.failed(row.id());
        metrics.outboxFailed();
        break;
      }
    }
  }

  void publishControl(String command, ApiModels.RuntimeView runtime) {
    if (!client.isConnected()) return;
    try {
      WarehouseStore.NodeRow home = store.locationPosition("PARK-01");
      ApiModels.AgvView agv = store.agv();
      boolean reset = "RESET".equals(command);
      java.util.Map<String, Object> control = new java.util.LinkedHashMap<>();
      control.put("command", command);
      control.put("epoch", runtime.simulationEpoch());
      control.put("x", reset ? home.x() : agv.x());
      control.put("z", reset ? home.z() : agv.z());
      control.put("theta", reset ? 0 : agv.theta());
      control.put("battery", reset ? 82 : agv.battery());
      control.put("timeScale", runtime.timeScale());
      control.put("charging", reset || agv.charging());
      control.put("handlingPhase", reset ? "CHARGING" : agv.handlingPhase());
      String stationId = reset ? "PARK-01" : agv.currentStationId();
      if (stationId != null) control.put("stationId", stationId);
      String payload = mapper.writeValueAsString(control);
      client.publish(Vda5050.TOPIC_PREFIX + "/control", payload.getBytes(StandardCharsets.UTF_8), 1, false);
    } catch (Exception exception) {
      throw new IllegalStateException("Could not send simulator control", exception);
    }
  }

  void publishInstantAction(String actionType, UUID orderId) {
    if (!client.isConnected()) throw new IllegalStateException("AGV broker is not connected");
    try {
      java.util.List<Vda5050.ActionParameter> parameters = orderId == null ? java.util.List.of()
          : java.util.List.of(new Vda5050.ActionParameter("orderId", orderId.toString()));
      Vda5050.Action action = new Vda5050.Action(actionType, UUID.randomUUID().toString(), "NONE", parameters);
      Vda5050.InstantActions request = new Vda5050.InstantActions(instantActionHeader.incrementAndGet(), Vda5050.now(),
          Vda5050.VERSION, Vda5050.MANUFACTURER, Vda5050.SERIAL_NUMBER, java.util.List.of(action));
      validator.validate("instantActions", request);
      client.publish(Vda5050.TOPIC_PREFIX + "/instantActions", mapper.writeValueAsBytes(request), 1, false);
    } catch (Exception exception) {
      throw new IllegalStateException("Could not publish VDA instant action", exception);
    }
  }

  private void subscribe(String topic, IMqttMessageListener listener) throws Exception {
    client.subscribe(topic, 0, (receivedTopic, message) -> {
      MqttMessage copy = new MqttMessage(message.getPayload().clone());
      mqttCallbacks.submit(() -> {
        try { listener.messageArrived(receivedTopic, copy); }
        catch (Exception exception) { events.publish("AGV_MESSAGE_REJECTED", java.util.Map.of("topic", receivedTopic, "error", String.valueOf(exception.getMessage()))); }
      });
    });
  }

  private void subscribeLatest(String topic, AtomicReference<InboundMessage> target) throws Exception {
    client.subscribe(topic, 0, (receivedTopic, message) -> {
      InboundMessage previous = target.getAndSet(new InboundMessage(receivedTopic, new MqttMessage(message.getPayload().clone())));
      if (previous != null) metrics.telemetryCoalesced(receivedTopic);
    });
  }

  @Scheduled(fixedDelay = 50, initialDelay = 1000)
  void consumeLatestTelemetry() {
    InboundMessage visualization = latestVisualization.getAndSet(null);
    if (visualization != null) onVisualization(visualization.topic(), visualization.message());
    InboundMessage handling = latestHandling.getAndSet(null);
    if (handling != null) onHandling(handling.topic(), handling.message());
  }

  @Scheduled(fixedDelay = 500, initialDelay = 1500)
  void persistLivePose() {
    ApiModels.AgvView agv = liveAgv.get();
    if (agv != null) store.updateAgvMotion(agv.x(), agv.z(), agv.theta(), agv.velocity(), agv.status(), agv.taskId());
  }

  private void onState(String topic, MqttMessage message) {
    try {
      String json = new String(message.getPayload(), StandardCharsets.UTF_8);
      validator.validate("state", json);
      Vda5050.State state = mapper.readValue(json, Vda5050.State.class);
      metrics.mqttAccepted(topic);
      if (state.powerSupply() != null) store.updatePower(state.powerSupply().stateOfCharge(), state.powerSupply().charging());
      UUID jobId = uuid(state.orderId());
      if (jobId != null) {
        if (hasInstantAction(state, "cancelOrder", "FINISHED")) {
          execution.cancelled(jobId);
          ApiModels.AgvView agv = store.agv();
          liveAgv.set(agv);
          events.publish("AGV_UPDATED", agv);
          return;
        }
        store.acceptDispatch(jobId);
        if (hasAction(state, "pick", "FINISHED")) execution.picked(jobId);
        else if (state.driving()) execution.executing(jobId);
        else if (state.actionStates().isEmpty() || state.actionStates().stream().allMatch(action -> "FINISHED".equals(action.get("actionStatus"))))
          execution.completeIfArrived(jobId, state.lastNodeId());
        if (state.newBaseRequest()) execution.releaseNext(jobId);
      }
      ApiModels.AgvView agv = store.agv();
      liveAgv.set(agv);
      events.publish("AGV_UPDATED", agv);
    } catch (Exception exception) {
      metrics.mqttRejected(topic);
      events.publish("AGV_MESSAGE_REJECTED", java.util.Map.of("topic", topic, "error", exception.getMessage()));
    }
  }

  private void onVisualization(String topic, MqttMessage message) {
    try {
      String json = new String(message.getPayload(), StandardCharsets.UTF_8);
      validator.validate("visualization", json);
      Vda5050.Visualization telemetry = mapper.readValue(json, Vda5050.Visualization.class);
      metrics.mqttAccepted(topic);
      Vda5050.Position position = telemetry.mobileRobotPosition();
      double speed = telemetry.velocity() == null ? 0 : Math.hypot(telemetry.velocity().vx(), telemetry.velocity().vy());
      var current = liveAgv.get();
      if (current == null) current = store.agv();
      String status = speed > 0.01 ? "MOVING" : current.status();
      if (speed <= 0.01 && current.taskId() == null && !java.util.Set.of("PARKING","CHARGING","PARKED","DOCKING").contains(current.status())) status = "IDLE";
      ApiModels.AgvView updated = new ApiModels.AgvView(current.id(), position.x(), position.y(), position.theta(), speed,
          current.battery(), status, current.taskId(), current.charging(), current.currentStationId(), current.handlingPhase(),
          current.forkHeight(), current.forkExtension(), current.carriedLoadId());
      liveAgv.set(updated);
      events.publish("AGV_POSE", updated);
      if (current.velocity() > 0.01 && speed <= 0.01 && current.taskId() == null) execution.agvIdle();
    } catch (Exception exception) {
      metrics.mqttRejected(topic);
      events.publish("AGV_MESSAGE_REJECTED", java.util.Map.of("topic", topic, "error", exception.getMessage()));
    }
  }

  @SuppressWarnings("unchecked")
  private void onHandling(String topic, MqttMessage message) {
    try {
      Map<String, Object> value = mapper.readValue(message.getPayload(), Map.class);
      metrics.mqttAccepted(topic);
      String phase = String.valueOf(value.getOrDefault("phase", "IDLE"));
      double forkHeight = ((Number) value.getOrDefault("forkHeight", 0)).doubleValue();
      double forkExtension = ((Number) value.getOrDefault("forkExtension", 0)).doubleValue();
      String loadId = nullable(value.get("loadId"));
      String stationId = nullable(value.get("stationId"));
      store.updateHandling(phase, forkHeight, forkExtension, loadId, stationId);
      ApiModels.AgvView agv = store.agv();
      liveAgv.set(agv);
      events.publish("AGV_HANDLING_UPDATED", agv);
      if ("CHARGING".equals(phase) || "PARKED".equals(phase)) execution.agvIdle();
    } catch (Exception exception) {
      metrics.mqttRejected(topic);
      events.publish("AGV_MESSAGE_REJECTED", java.util.Map.of("topic", topic, "error", exception.getMessage()));
    }
  }

  private static String nullable(Object value) {
    if (value == null) return null;
    String text = String.valueOf(value);
    return text.isBlank() || "null".equals(text) ? null : text;
  }

  private void onConnection(String topic, MqttMessage message) {
    try {
      String json = new String(message.getPayload(), StandardCharsets.UTF_8);
      validator.validate("connection", json);
      Vda5050.Connection connection = mapper.readValue(json, Vda5050.Connection.class);
      metrics.mqttAccepted(topic);
      events.publish("AGV_CONNECTION_UPDATED", connection);
      if ("ONLINE".equals(connection.connectionState())) publishControl("SYNC", store.runtime());
    } catch (Exception exception) {
      metrics.mqttRejected(topic);
      events.publish("AGV_MESSAGE_REJECTED", java.util.Map.of("topic", topic, "error", exception.getMessage()));
    }
  }

  private static UUID uuid(String value) {
    try { return value == null || value.isBlank() ? null : UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
  }

  private static boolean hasAction(Vda5050.State state, String type, String status) {
    return state.actionStates().stream().anyMatch(action -> type.equals(action.get("actionType")) && status.equals(action.get("actionStatus")));
  }

  private static boolean hasInstantAction(Vda5050.State state, String type, String status) {
    return state.instantActionStates().stream().anyMatch(action -> type.equals(action.get("actionType")) && status.equals(action.get("actionStatus")));
  }

  @PreDestroy void close() throws Exception {
    mqttCallbacks.shutdownNow();
    if (client.isConnected()) client.disconnect();
    client.close();
  }
}
