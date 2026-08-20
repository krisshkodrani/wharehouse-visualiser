package com.example.warehouse.mqtt;

import com.example.warehouse.ApiModels;
import com.example.warehouse.transport.JobExecutionService;
import com.example.warehouse.WarehouseStore;
import com.example.warehouse.events.EventPublisher;
import com.example.warehouse.observability.LogContext;
import com.example.warehouse.observability.WarehouseMetrics;
import com.example.warehouse.vda.Vda5050;
import com.example.warehouse.config.WarehouseProperties;
import com.example.warehouse.persistence.outbox.OutboxRepository;
import com.example.warehouse.persistence.outbox.OutboxPublisher;
import com.example.warehouse.persistence.outbox.OutboxService;
import com.example.warehouse.vda5050.VdaInstantActionService;
import com.example.warehouse.vda5050.VdaConnectionHandler;
import com.example.warehouse.vda5050.VdaHandlingHandler;
import com.example.warehouse.vda5050.VdaMessageValidator;
import com.example.warehouse.vda5050.VdaStateHandler;
import com.example.warehouse.vda5050.VdaVisualizationHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MqttGateway {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MqttGateway.class);
  private final ObjectMapper mapper;
  private final WarehouseStore store;
  private final JobExecutionService execution;
  private final EventPublisher events;
  private final WarehouseMetrics metrics;
  private final OutboxPublisher outboxPublisher;
  private final VdaMessageValidator validator;
  private final VdaInstantActionService instantActions = new VdaInstantActionService();
  private final VdaConnectionHandler connectionHandler;
  private final VdaHandlingHandler handlingHandler;
  private final VdaStateHandler stateHandler;
  private final VdaVisualizationHandler visualizationHandler;
  private final MqttConnection connection;
  private final MqttPublisher publisher;
  private final MqttSubscriber subscriber;
  private final ExecutorService mqttCallbacks = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("backend-mqtt-callback").factory());
  private final ConcurrentHashMap<String, InboundMessage> latestVisualizations = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, InboundMessage> latestHandlings = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ApiModels.AgvView> liveAgvs = new ConcurrentHashMap<>();
  private final AtomicLong instantActionHeader = new AtomicLong();

  private record InboundMessage(String topic, MqttMessage message) {}

  public MqttGateway(ObjectMapper mapper, WarehouseStore store, JobExecutionService execution, EventPublisher events, WarehouseMetrics metrics,
      WarehouseProperties properties, OutboxRepository outbox) throws Exception {
    this.mapper = mapper; this.store = store; this.execution = execution; this.events = events; this.metrics = metrics;
    this.validator = new VdaMessageValidator(mapper);
    this.connectionHandler = new VdaConnectionHandler(mapper, validator, events, metrics,
        agvId -> publishControl("SYNC", store.runtime(), agvId));
    this.handlingHandler = new VdaHandlingHandler(mapper, store, execution, events, metrics, liveAgvs);
    this.stateHandler = new VdaStateHandler(
        mapper, validator, instantActions, store, execution, events, metrics, liveAgvs);
    this.visualizationHandler = new VdaVisualizationHandler(
        mapper, validator, store, execution, events, metrics, liveAgvs);
    this.connection = new MqttConnection(properties);
    this.publisher = new MqttPublisher(connection);
    this.subscriber = new MqttSubscriber(connection);
    this.outboxPublisher =
        new OutboxPublisher(new OutboxService(outbox), connection, publisher, metrics);
  }

  @Scheduled(fixedDelay = 5000, initialDelay = 1000)
  void ensureConnected() {
    if (connection.isConnected()) return;
    try {
      connection.connect();
      // Control messages are commands, not durable state. Remove any retained
      // command left by an older deployment before the simulator subscribes.
      for (String agvId : store.agvIds()) publisher.publish(TopicFactory.control(agvId), new byte[0], 1, true);
      subscribe(TopicFactory.state("+"), this::onState);
      subscribeLatest(TopicFactory.visualization("+"), latestVisualizations);
      subscribe(TopicFactory.connection("+"), this::onConnection);
      subscribeLatest(TopicFactory.handling("+"), latestHandlings);
    } catch (Exception ignored) {
      // Health remains degraded through broker connectivity; retry on the next scheduled tick.
    }
  }

  @Scheduled(fixedDelay = 250, initialDelay = 2500)
  void publishOutbox() {
    outboxPublisher.publishPending();
  }

  public void publishControl(String command, ApiModels.RuntimeView runtime) {
    for (String agvId : store.agvIds()) publishControl(command, runtime, agvId);
  }

  private void publishControl(String command, ApiModels.RuntimeView runtime, String agvId) {
    if (!connection.isConnected()) return;
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
      publisher.publish(TopicFactory.control(agvId), payload.getBytes(StandardCharsets.UTF_8), 1, false);
    } catch (Exception exception) {
      throw new IllegalStateException("Could not send simulator control", exception);
    }
  }

  public void publishInstantAction(String actionType, UUID orderId) {
    if (!connection.isConnected()) throw new IllegalStateException("AGV broker is not connected");
    try {
      java.util.List<Vda5050.ActionParameter> parameters = orderId == null ? java.util.List.of()
          : java.util.List.of(new Vda5050.ActionParameter("orderId", orderId.toString()));
      Vda5050.Action action = new Vda5050.Action(actionType, UUID.randomUUID().toString(), "NONE", parameters);
      List<String> targets = orderId == null ? store.agvIds() : java.util.List.of(store.agvIdForTask(orderId).orElse("FL-01"));
      for (String agvId : targets) {
        Vda5050.InstantActions request = new Vda5050.InstantActions(instantActionHeader.incrementAndGet(), Vda5050.now(),
            Vda5050.VERSION, Vda5050.MANUFACTURER, agvId, java.util.List.of(action));
        validator.validate("instantActions", request);
        publisher.publish(TopicFactory.instantActions(agvId), mapper.writeValueAsBytes(request), 1, false);
      }
    } catch (Exception exception) {
      throw new IllegalStateException("Could not publish VDA instant action", exception);
    }
  }

  private void subscribe(String topic, IMqttMessageListener listener) throws Exception {
    subscriber.subscribe(topic, 0, (receivedTopic, message) -> {
      MqttMessage copy = new MqttMessage(message.getPayload().clone());
      mqttCallbacks.submit(() -> {
        try { listener.messageArrived(receivedTopic, copy); }
        catch (Exception exception) { events.publish("AGV_MESSAGE_REJECTED", java.util.Map.of("topic", receivedTopic, "error", String.valueOf(exception.getMessage()))); }
      });
    });
  }

  private void subscribeLatest(String topic, ConcurrentHashMap<String, InboundMessage> target) throws Exception {
    subscriber.subscribe(topic, 0, (receivedTopic, message) -> {
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
  public void persistLivePose() {
    liveAgvs.forEach((id, agv) -> store.updateAgvMotion(id, agv.x(), agv.z(), agv.theta(), agv.velocity(), agv.status(), agv.taskId()));
  }

  // Package-private so MqttGatewayTest can drive a state snapshot without a broker.
  public void onState(String topic, MqttMessage message) {
    stateHandler.handle(topic, message);
  }

  // Package-private for MqttGatewayTest; see onState.
  public void onVisualization(String topic, MqttMessage message) {
    visualizationHandler.handle(topic, message);
  }

  @SuppressWarnings("unchecked")
  private void onHandling(String topic, MqttMessage message) {
    handlingHandler.handle(topic, message);
  }

  private void onConnection(String topic, MqttMessage message) {
    connectionHandler.handle(topic, message);
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
  /**
   * Keeps the live pose while taking lifecycle columns from the database.
   *
   * <p>The database row is only as fresh as the last {@link #persistLivePose} tick,
   * so publishing it verbatim rewinds the vehicle by up to half a second on every
   * state or handling message, which reads as stutter in the 3D view and undoes the
   * interpolation the client just applied.
   */
  @PreDestroy void close() throws Exception {
    mqttCallbacks.shutdownNow();
    connection.close();
  }
}
