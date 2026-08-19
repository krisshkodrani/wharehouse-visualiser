package com.example.warehouse;

import com.example.warehouse.vda.Vda5050;
import com.example.warehouse.vda.VdaSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MqttGateway.class);
  private final ObjectMapper mapper;
  private final WarehouseStore store;
  private final JobExecutionService execution;
  private final EventPublisher events;
  private final WarehouseMetrics metrics;
  private final VdaSchemaValidator validator;
  private final MqttClient client;
  private final MqttConnectOptions options;
  private final ExecutorService mqttCallbacks = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("backend-mqtt-callback").factory());
  private final ConcurrentHashMap<String, InboundMessage> latestVisualizations = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, InboundMessage> latestHandlings = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ApiModels.AgvView> liveAgvs = new ConcurrentHashMap<>();
  private final java.util.Set<String> handledInstantActions = ConcurrentHashMap.newKeySet();
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
      for (String agvId : store.agvIds()) client.publish(Vda5050.topicPrefix(agvId) + "/control", new byte[0], 1, true);
      subscribe(Vda5050.topicPrefix("+") + "/state", this::onState);
      subscribeLatest(Vda5050.topicPrefix("+") + "/visualization", latestVisualizations);
      subscribe(Vda5050.topicPrefix("+") + "/connection", this::onConnection);
      subscribeLatest(Vda5050.topicPrefix("+") + "/handling", latestHandlings);
    } catch (Exception ignored) {
      // Health remains degraded through broker connectivity; retry on the next scheduled tick.
    }
  }

  @Scheduled(fixedDelay = 250, initialDelay = 2500)
  void publishOutbox() {
    // A disconnected broker stalls every command in the system, and used to do so in
    // complete silence. Logged at debug so a healthy demo stays quiet, because this
    // runs every 250 ms.
    if (!client.isConnected()) {
      if (log.isDebugEnabled()) {
        try (var scope = LogContext.of(LogContext.EVENT, "OUTBOX_STALLED")
            .and(LogContext.REASON, "BROKER_DISCONNECTED").open()) {
          log.debug("outbox not drained: MQTT client is not connected");
        }
      }
      return;
    }
    for (WarehouseStore.OutboxRow row : store.pendingOutbox()) {
      try {
        client.publish(row.topic(), row.payload().getBytes(StandardCharsets.UTF_8), row.qos(), false);
        store.sent(row.id());
        metrics.outboxPublished();
        try (var scope = LogContext.of(LogContext.EVENT, "OUTBOX_PUBLISHED").open()) {
          log.debug("published {} (qos {})", row.topic(), row.qos());
        }
      } catch (Exception exception) {
        store.failed(row.id());
        metrics.outboxFailed();
        // Warn, not debug: delivery is at-least-once and this is the moment a command
        // failed to leave the building. The break means everything behind it waits.
        try (var scope = LogContext.of(LogContext.EVENT, "OUTBOX_PUBLISH_FAILED")
            .and(LogContext.REASON, exception.getClass().getSimpleName()).open()) {
          log.warn("could not publish {}: {}", row.topic(), String.valueOf(exception.getMessage()));
        }
        break;
      }
    }
  }

  void publishControl(String command, ApiModels.RuntimeView runtime) {
    for (String agvId : store.agvIds()) publishControl(command, runtime, agvId);
  }

  private void publishControl(String command, ApiModels.RuntimeView runtime, String agvId) {
    if (!client.isConnected()) return;
    try {
      // V20 made the fleet single-vehicle, so PARK-01 is the only home. A second
      // vehicle needs more than another parking id — see docs/ARCHITECTURE.md.
      String homeId = "PARK-01";
      WarehouseStore.NodeRow home = store.locationPosition(homeId);
      ApiModels.AgvView agv = store.agv(agvId);
      boolean reset = "RESET".equals(command);
      java.util.Map<String, Object> control = new java.util.LinkedHashMap<>();
      control.put("command", command);
      control.put("epoch", runtime.simulationEpoch());
      control.put("x", reset ? home.x() : agv.x());
      control.put("z", reset ? home.z() : agv.z());
      control.put("theta", reset ? 0 : agv.theta());
      // The store has already applied either the reset baseline or the selected
      // scenario preset. Keep it authoritative instead of overriding presets
      // with a transport-layer demo constant.
      control.put("battery", agv.battery());
      control.put("timeScale", runtime.timeScale());
      control.put("charging", reset || agv.charging());
      control.put("handlingPhase", reset ? "CHARGING" : agv.handlingPhase());
      String stationId = reset ? homeId : agv.currentStationId();
      if (stationId != null) control.put("stationId", stationId);
      String payload = mapper.writeValueAsString(control);
      client.publish(Vda5050.topicPrefix(agvId) + "/control", payload.getBytes(StandardCharsets.UTF_8), 1, false);
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
      List<String> targets = orderId == null ? store.agvIds() : java.util.List.of(store.agvIdForTask(orderId).orElse("FL-01"));
      for (String agvId : targets) {
        Vda5050.InstantActions request = new Vda5050.InstantActions(instantActionHeader.incrementAndGet(), Vda5050.now(),
            Vda5050.VERSION, Vda5050.MANUFACTURER, agvId, java.util.List.of(action));
        validator.validate("instantActions", request);
        client.publish(Vda5050.topicPrefix(agvId) + "/instantActions", mapper.writeValueAsBytes(request), 1, false);
      }
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

  private void subscribeLatest(String topic, ConcurrentHashMap<String, InboundMessage> target) throws Exception {
    client.subscribe(topic, 0, (receivedTopic, message) -> {
      String agvId = serialFromTopic(receivedTopic);
      InboundMessage previous = target.put(agvId, new InboundMessage(receivedTopic, new MqttMessage(message.getPayload().clone())));
      if (previous != null) metrics.telemetryCoalesced(receivedTopic);
    });
  }

  @Scheduled(fixedDelay = 50, initialDelay = 1000)
  void consumeLatestTelemetry() {
    latestVisualizations.forEach((id, message) -> { if (latestVisualizations.remove(id, message)) onVisualization(message.topic(), message.message()); });
    latestHandlings.forEach((id, message) -> { if (latestHandlings.remove(id, message)) onHandling(message.topic(), message.message()); });
  }

  @Scheduled(fixedDelay = 500, initialDelay = 1500)
  void persistLivePose() {
    liveAgvs.forEach((id, agv) -> store.updateAgvMotion(id, agv.x(), agv.z(), agv.theta(), agv.velocity(), agv.status(), agv.taskId()));
  }

  // Package-private so MqttGatewayTest can drive a state snapshot without a broker.
  void onState(String topic, MqttMessage message) {
    try {
      String json = new String(message.getPayload(), StandardCharsets.UTF_8);
      validator.validate("state", json);
      Vda5050.State state = mapper.readValue(json, Vda5050.State.class);
      String agvId = serialFromTopic(topic);
      metrics.mqttAccepted(topic);
      if (state.powerSupply() != null) store.updatePower(agvId, state.powerSupply().stateOfCharge(), state.powerSupply().charging());
      UUID jobId = uuid(state.orderId());
      // A park or charge move is a real VDA order with no transport task behind it, so
      // there is nothing to accept or transition -- but it is ours, and reporting state
      // against it is correct vehicle behaviour, not a protocol violation. Treating it as
      // an unknown task rejected every state message for the length of the drive, put a
      // false error in front of the operator, and skipped the AGV_UPDATED below with it.
      if (jobId != null && store.isHousekeepingOrder(state.orderId())) jobId = null;
      if (jobId != null) {
        if (consumeInstantAction(state, "cancelOrder")) {
          execution.cancelled(jobId);
          ApiModels.AgvView agv = withLivePose(store.agv(agvId), liveAgvs.get(agvId));
          liveAgvs.put(agvId, agv);
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
      ApiModels.AgvView agv = withLivePose(store.agv(agvId), liveAgvs.get(agvId));
      liveAgvs.put(agvId, agv);
      events.publish("AGV_UPDATED", agv);
    } catch (Exception exception) {
      metrics.mqttRejected(topic);
      try (var scope = LogContext.of(LogContext.EVENT, "AGV_MESSAGE_REJECTED")
          .and(LogContext.REASON, exception.getClass().getSimpleName())
          .and(LogContext.VEHICLE_ID, serialFromTopic(topic)).open()) {
        // Inbound validation failures must not mutate domain state, so this log line
        // and the event below are the only trace they leave.
        log.warn("rejected inbound {}: {}", topic, String.valueOf(exception.getMessage()));
      }
      events.publish("AGV_MESSAGE_REJECTED", java.util.Map.of("topic", topic, "error", String.valueOf(exception.getMessage())));
    }
  }

  // Package-private for MqttGatewayTest; see onState.
  void onVisualization(String topic, MqttMessage message) {
    try {
      String json = new String(message.getPayload(), StandardCharsets.UTF_8);
      validator.validate("visualization", json);
      Vda5050.Visualization telemetry = mapper.readValue(json, Vda5050.Visualization.class);
      String agvId = serialFromTopic(topic);
      metrics.mqttAccepted(topic);
      Vda5050.Position position = telemetry.mobileRobotPosition();
      double speed = telemetry.velocity() == null ? 0 : Math.hypot(telemetry.velocity().vx(), telemetry.velocity().vy());
      var current = liveAgvs.get(agvId);
      if (current == null) current = store.agv(agvId);
      String status = speed > 0.01 ? "MOVING" : current.status();
      if (speed <= 0.01 && current.taskId() == null && !java.util.Set.of("PARKING","CHARGING","PARKED","DOCKING").contains(current.status())) status = "IDLE";
      ApiModels.AgvView updated = new ApiModels.AgvView(current.id(), position.x(), position.y(), position.theta(), speed,
          current.battery(), status, current.taskId(), current.charging(), current.currentStationId(), current.handlingPhase(),
          current.forkHeight(), current.forkExtension(), current.carriedLoadId());
      liveAgvs.put(agvId, updated);
      events.publish("AGV_POSE", updated);
      if (current.velocity() > 0.01 && speed <= 0.01 && current.taskId() == null) execution.agvIdle();
    } catch (Exception exception) {
      metrics.mqttRejected(topic);
      try (var scope = LogContext.of(LogContext.EVENT, "AGV_MESSAGE_REJECTED")
          .and(LogContext.REASON, exception.getClass().getSimpleName())
          .and(LogContext.VEHICLE_ID, serialFromTopic(topic)).open()) {
        // Inbound validation failures must not mutate domain state, so this log line
        // and the event below are the only trace they leave.
        log.warn("rejected inbound {}: {}", topic, String.valueOf(exception.getMessage()));
      }
      events.publish("AGV_MESSAGE_REJECTED", java.util.Map.of("topic", topic, "error", String.valueOf(exception.getMessage())));
    }
  }

  @SuppressWarnings("unchecked")
  private void onHandling(String topic, MqttMessage message) {
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
        // Inbound validation failures must not mutate domain state, so this log line
        // and the event below are the only trace they leave.
        log.warn("rejected inbound {}: {}", topic, String.valueOf(exception.getMessage()));
      }
      events.publish("AGV_MESSAGE_REJECTED", java.util.Map.of("topic", topic, "error", String.valueOf(exception.getMessage())));
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
      if ("ONLINE".equals(connection.connectionState())) publishControl("SYNC", store.runtime(), serialFromTopic(topic));
    } catch (Exception exception) {
      metrics.mqttRejected(topic);
      try (var scope = LogContext.of(LogContext.EVENT, "AGV_MESSAGE_REJECTED")
          .and(LogContext.REASON, exception.getClass().getSimpleName())
          .and(LogContext.VEHICLE_ID, serialFromTopic(topic)).open()) {
        // Inbound validation failures must not mutate domain state, so this log line
        // and the event below are the only trace they leave.
        log.warn("rejected inbound {}: {}", topic, String.valueOf(exception.getMessage()));
      }
      events.publish("AGV_MESSAGE_REJECTED", java.util.Map.of("topic", topic, "error", String.valueOf(exception.getMessage())));
    }
  }

  private static UUID uuid(String value) {
    try { return value == null || value.isBlank() ? null : UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
  }

  /**
   * Extracts the vehicle serial from a VDA 5050 topic.
   *
   * <p>The layout is {@code vda5050/<major>/<manufacturer>/<serial>/<messageType>}, so the serial is
   * segment 3. Reading segment 4 returned the message type instead, and every inbound telemetry
   * handler then looked up a vehicle called "state"/"visualization"/"handling", threw, and aborted —
   * poses, battery, and handling phases never reached the database or the browser.
   */
  private static String serialFromTopic(String topic) {
    String[] parts = topic.split("/");
    return parts.length >= 5 ? parts[3] : Vda5050.SERIAL_NUMBER;
  }

  private static boolean hasAction(Vda5050.State state, String type, String status) {
    return state.actionStates().stream().anyMatch(action -> type.equals(action.get("actionType")) && status.equals(action.get("actionStatus")));
  }

  /**
   * Consumes a finished instant action exactly once.
   *
   * <p>A VDA 5050 state message is a full snapshot, not an event: a vehicle keeps
   * reporting an instant action's terminal status in every later state message, and
   * the action outlives the order it was issued against. Reacting to its mere
   * presence therefore re-executes the command forever — a lingering finished
   * {@code cancelOrder} cancelled every subsequent order within one state tick, so
   * the fleet dispatched and cancelled in a loop and never moved. Action ids are
   * unique per command, so tracking the ones already handled makes the reaction
   * idempotent no matter how long the vehicle repeats them.
   */
  private boolean consumeInstantAction(Vda5050.State state, String type) {
    String actionId = state.instantActionStates().stream()
        .filter(action -> type.equals(action.get("actionType")) && "FINISHED".equals(action.get("actionStatus")))
        .map(action -> String.valueOf(action.get("actionId")))
        .filter(id -> !handledInstantActions.contains(id))
        .findFirst().orElse(null);
    if (actionId == null) return false;
    // Ids are random UUIDs and only one is minted per command, so the set grows
    // slowly; clear it wholesale rather than tracking insertion order.
    if (handledInstantActions.size() > 512) handledInstantActions.clear();
    return handledInstantActions.add(actionId);
  }

  /**
   * Keeps the live pose while taking lifecycle columns from the database.
   *
   * <p>The database row is only as fresh as the last {@link #persistLivePose} tick,
   * so publishing it verbatim rewinds the vehicle by up to half a second on every
   * state or handling message, which reads as stutter in the 3D view and undoes the
   * interpolation the client just applied.
   */
  private ApiModels.AgvView withLivePose(ApiModels.AgvView stored, ApiModels.AgvView live) {
    if (live == null) return stored;
    return new ApiModels.AgvView(stored.id(), live.x(), live.z(), live.theta(), live.velocity(),
        stored.battery(), stored.status(), stored.taskId(), stored.charging(), stored.currentStationId(),
        stored.handlingPhase(), stored.forkHeight(), stored.forkExtension(), stored.carriedLoadId());
  }

  @PreDestroy void close() throws Exception {
    mqttCallbacks.shutdownNow();
    if (client.isConnected()) client.disconnect();
    client.close();
  }
}
