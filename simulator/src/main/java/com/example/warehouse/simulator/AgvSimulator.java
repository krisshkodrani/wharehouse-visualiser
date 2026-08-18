package com.example.warehouse.simulator;

import com.example.warehouse.vda.Vda5050;
import com.example.warehouse.vda.VdaSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class AgvSimulator {
  private final String serialNumber;
  private final ObjectMapper mapper;
  private final VdaSchemaValidator validator;
  private final MqttClient client;
  private final MqttConnectOptions options;
  private final double emptySpeed;
  private final double loadedSpeed;
  private final double dockingSpeed;
  private final double acceleration;
  private final double braking;
  private final double batteryConsumptionPerMetre;
  private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("agv-motion").factory());
  private final ExecutorService mqttCallbacks = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("agv-mqtt-callback").factory());
  private final ScheduledExecutorService charger = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("agv-charger").factory());
  private final ScheduledExecutorService telemetryPublisher = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("agv-telemetry").factory());
  private final Set<String> completedOrders = Collections.newSetFromMap(new ConcurrentHashMap<>());
  private final AtomicLong stateHeader = new AtomicLong();
  private final AtomicLong visualizationHeader = new AtomicLong();
  private final AtomicLong instantActionHeader = new AtomicLong();
  private final AtomicLong simulationEpoch = new AtomicLong(1);
  private final CountDownLatch initialSync = new CountDownLatch(1);
  private volatile String activeOrder;
  private final AtomicReference<Vda5050.Order> activeOrderPayload = new AtomicReference<>();
  private volatile boolean cancelRequested;
  private volatile String lastNodeId = "";
  private volatile long lastNodeSequenceId;
  private volatile List<Map<String, Object>> instantActionStates = List.of();
  private volatile boolean paused;
  private volatile int timeScale = 2;
  private volatile double battery = 82;
  private volatile double velocity;
  private volatile double forkHeight;
  private volatile double forkExtension;
  private volatile String handlingPhase = "CHARGING";
  private volatile String carriedLoadId;
  private volatile String currentStationId = "PARK-01";
  private volatile boolean charging = true;
  private volatile boolean visualizationDirty;
  private volatile boolean handlingDirty;
  private volatile String telemetryLoadId;
  private volatile Vda5050.Position position = new Vda5050.Position(11, -6, 0, "linz", true);

  @Autowired
  AgvSimulator(ObjectMapper mapper,
      @Value("${warehouse.mqtt-url}") String url,
      @Value("${warehouse.mqtt-user}") String user,
      @Value("${warehouse.mqtt-password}") String password,
      @Value("${warehouse.speed:2.5}") double emptySpeed,
      @Value("${warehouse.loaded-speed:1.8}") double loadedSpeed,
      @Value("${warehouse.docking-speed:0.7}") double dockingSpeed,
      @Value("${warehouse.acceleration:1.0}") double acceleration,
      @Value("${warehouse.braking:1.5}") double braking,
      @Value("${warehouse.battery-consumption-per-metre:0.015}") double batteryConsumptionPerMetre) throws Exception {
    this(mapper, "FL-01", url, user, password, emptySpeed, loadedSpeed, dockingSpeed, acceleration, braking, batteryConsumptionPerMetre);
  }

  AgvSimulator(ObjectMapper mapper, String serialNumber, String url, String user, String password,
      double emptySpeed, double loadedSpeed, double dockingSpeed, double acceleration, double braking,
      double batteryConsumptionPerMetre) throws Exception {
    this.serialNumber = serialNumber;
    this.mapper = mapper; this.validator = new VdaSchemaValidator(mapper); this.emptySpeed = emptySpeed;
    this.loadedSpeed = loadedSpeed; this.dockingSpeed = dockingSpeed; this.acceleration = acceleration; this.braking = braking;
    BatteryModel.consume(100, 0, batteryConsumptionPerMetre);
    this.batteryConsumptionPerMetre = batteryConsumptionPerMetre;
    this.client = new MqttClient(url, "agv-" + serialNumber, new MemoryPersistence());
    this.options = new MqttConnectOptions();
    options.setCleanSession(true);
    options.setAutomaticReconnect(true);
    options.setUserName(user);
    options.setPassword(password.toCharArray());
    options.setConnectionTimeout(10);
    options.setWill(Vda5050.topicPrefix(serialNumber) + "/connection", bytes(connection("CONNECTION_BROKEN")), 1, true);
  }

  @PostConstruct
  void start() throws Exception {
    client.setCallback(new MqttCallbackExtended() {
      @Override public void connectComplete(boolean reconnect, String serverUri) {
        if (!reconnect) return;
        try {
          subscribeTopics();
          publish("connection", connection("ONLINE"), 1, true);
        } catch (Exception exception) {
          System.err.println("MQTT resubscribe failed: " + exception.getMessage());
        }
      }

      @Override public void connectionLost(Throwable cause) {
        telemetry("MQTT_DISCONNECTED", "reason=" + (cause == null ? "unknown" : cause.getMessage()));
      }

      @Override public void messageArrived(String topic, MqttMessage message) {
        MqttMessage copy = new MqttMessage(message.getPayload().clone());
        mqttCallbacks.submit(() -> {
          if (topic.endsWith("/order")) receive(copy);
          else if (topic.endsWith("/instantActions")) instantActions(copy);
          else if (topic.endsWith("/control")) control(copy);
        });
      }

      @Override public void deliveryComplete(IMqttDeliveryToken token) {}
    });
    client.connect(options);
    subscribeTopics();
    publish("connection", connection("ONLINE"), 1, true);
    initialSync.await(2, TimeUnit.SECONDS);
    publishState("", currentStationId == null ? "" : currentStationId, 0, false, List.of());
    publishVisualization(0);
    publishHandling();
    telemetryPublisher.scheduleWithFixedDelay(this::flushTelemetry, 0, 50, TimeUnit.MILLISECONDS);
    charger.scheduleAtFixedRate(this::chargeTick, 1, 1, TimeUnit.SECONDS);
  }

  private void subscribeTopics() throws Exception {
    client.subscribe(new String[] {Vda5050.topicPrefix(serialNumber) + "/order", Vda5050.topicPrefix(serialNumber) + "/instantActions", Vda5050.topicPrefix(serialNumber) + "/control"}, new int[] {1, 1, 1});
    telemetry("MQTT_SUBSCRIBED", "topics=order,instantActions,control");
  }

  private void receive(MqttMessage message) {
    try {
      String json = new String(message.getPayload(), StandardCharsets.UTF_8);
      validator.validate("order", json);
      Vda5050.Order order = mapper.readValue(json, Vda5050.Order.class);
      if (order.orderId().equals(activeOrder)) {
        Vda5050.Order current = activeOrderPayload.get();
        if (current != null && order.orderUpdateId() > current.orderUpdateId()) {
          activeOrderPayload.set(order);
          telemetry("ORDER_UPDATE_ACCEPTED", "order=" + order.orderId() + " update=" + order.orderUpdateId());
          publishState(order.orderId(), lastNodeId, lastNodeSequenceId, false, List.of());
        }
        return;
      }
      if (completedOrders.contains(order.orderId())) return;
      if (activeOrder != null) return;
      charging = false;
      handlingPhase = "IDLE";
      currentStationId = null;
      activeOrder = order.orderId();
      activeOrderPayload.set(order);
      cancelRequested = false;
      // Instant actions belong to the order they were issued against. Reporting a
      // finished cancelOrder alongside a freshly accepted order tells the master
      // control that this order was cancelled too.
      instantActionStates = List.of();
      publishHandling();
      telemetry("ORDER_ACCEPTED", "order=" + order.orderId() + " nodes=" + order.nodes().size());
      long epoch = simulationEpoch.get();
      executor.submit(() -> execute(order, epoch));
    } catch (Exception exception) {
      System.err.println("Rejected order: " + exception.getMessage());
    }
  }

  private void execute(Vda5050.Order order, long epoch) {
    try {
      awaitRunning(epoch);
      List<Vda5050.Node> nodes = order.nodes();
      Vda5050.Node first = nodes.getFirst();
      awaitReleased(0, epoch);
      if (Math.hypot(position.x() - first.nodePosition().x(), position.y() - first.nodePosition().y()) > .01) {
        Vda5050.Node current = new Vda5050.Node("CURRENT", 0, true,
            new Vda5050.NodePosition(position.x(), position.y(), "linz", new Vda5050.AllowedDeviationXY(.25, .25, 0)), List.of());
        drive(current, first, epoch);
      }
      position = new Vda5050.Position(first.nodePosition().x(), first.nodePosition().y(), position.theta(), "linz", true);
      lastNodeId = first.nodeId();
      lastNodeSequenceId = first.sequenceId();
      executeActions(first.actions(), order, first, epoch);
      boolean firstIsLast = nodes.size() == 1;
      if (firstIsLast) {
        completedOrders.add(order.orderId());
        activeOrder = null;
      }
      publishState(order.orderId(), first.nodeId(), first.sequenceId(), !firstIsLast, actionStates(first.actions(), "FINISHED"));
      for (int index = 1; index < nodes.size(); index++) {
        awaitReleased(index, epoch);
        Vda5050.Order currentOrder = activeOrderPayload.get();
        Vda5050.Node from = currentOrder.nodes().get(index - 1);
        Vda5050.Node to = currentOrder.nodes().get(index);
        drive(from, to, epoch);
        executeActions(to.actions(), currentOrder, to, epoch);
        lastNodeId = to.nodeId();
        lastNodeSequenceId = to.sequenceId();
        boolean driving = index < nodes.size() - 1;
        if (!driving) {
          completedOrders.add(order.orderId());
          activeOrder = null;
        }
        publishState(order.orderId(), to.nodeId(), to.sequenceId(), driving, actionStates(to.actions(), "FINISHED"));
      }
      publishVisualization(0);
      telemetry("ORDER_COMPLETED", "order=" + order.orderId() + " x=" + position.x() + " z=" + position.y());
    } catch (Exception exception) {
      if (cancelRequested) {
        velocity = 0;
        publishVisualization(0);
        telemetry("ORDER_CANCELLED", "order=" + order.orderId());
      } else System.err.println("Order failed: " + exception.getMessage());
    } finally {
      if (order.orderId().equals(activeOrder)) activeOrder = null;
      activeOrderPayload.set(null);
      cancelRequested = false;
    }
  }

  private void awaitReleased(int nodeIndex, long epoch) throws Exception {
    while (true) {
      awaitRunning(epoch);
      Vda5050.Order current = activeOrderPayload.get();
      if (current != null && nodeIndex < current.nodes().size() && current.nodes().get(nodeIndex).released()) return;
      publishState(activeOrder == null ? "" : activeOrder, lastNodeId, lastNodeSequenceId, false, List.of());
      controlledSleep(100, epoch);
    }
  }

  private void drive(Vda5050.Node from, Vda5050.Node to, long epoch) throws Exception {
    double endX = to.nodePosition().x();
    double endY = to.nodePosition().y();
    double distance = Math.hypot(endX - position.x(), endY - position.y());
    telemetry("LEG_STARTED", "from=" + from.nodeId() + " to=" + to.nodeId() + " distance=" + String.format(java.util.Locale.ROOT, "%.2f", distance));
    double limit = to.actions().isEmpty() ? (carriedLoadId == null ? emptySpeed : loadedSpeed) : dockingSpeed;
    driveTo(endX, endY, limit, epoch);
    telemetry("LEG_ARRIVED", "node=" + to.nodeId() + " x=" + endX + " z=" + endY);
  }

  private void driveTo(double endX, double endY, double speedLimit, long epoch) throws Exception {
    double startX = position.x();
    double startY = position.y();
    double distance = Math.hypot(endX - startX, endY - startY);
    if (distance < .005) return;
    double theta = Math.atan2(endY - startY, endX - startX);
    turnTo(theta, epoch);
    double travelled = 0;
    double currentSpeed = 0;
    final double dt = .05;
    while (travelled < distance - .001) {
      awaitRunning(epoch);
      double remaining = distance - travelled;
      currentSpeed = MotionProfile.nextSpeed(currentSpeed, remaining, speedLimit, acceleration, braking, dt);
      double step = Math.min(remaining, currentSpeed * dt);
      travelled += step;
      double progress = travelled / distance;
      position = new Vda5050.Position(startX + (endX - startX) * progress, startY + (endY - startY) * progress, theta, "linz", true);
      velocity = currentSpeed;
      battery = BatteryModel.consume(battery, step, batteryConsumptionPerMetre);
      publishVisualization(currentSpeed);
      controlledSleep(50, epoch);
    }
    position = new Vda5050.Position(endX, endY, theta, "linz", true);
    velocity = 0;
    publishVisualization(0);
  }

  private void turnTo(double target, long epoch) throws Exception {
    double delta = normalize(target - position.theta());
    final double yawPerTick = Math.toRadians(60) * .05;
    while (Math.abs(delta) > .01) {
      awaitRunning(epoch);
      double step = Math.copySign(Math.min(Math.abs(delta), yawPerTick), delta);
      position = new Vda5050.Position(position.x(), position.y(), normalize(position.theta() + step), "linz", true);
      velocity = 0;
      publishVisualization(0);
      controlledSleep(50, epoch);
      delta = normalize(target - position.theta());
    }
  }

  private void executeActions(List<Vda5050.Action> actions, Vda5050.Order order, Vda5050.Node node, long epoch) throws Exception {
    for (Vda5050.Action action : actions) {
      awaitRunning(epoch);
      telemetry("ACTION_STARTED", "type=" + action.actionType() + " node=" + node.nodeId() + " order=" + order.orderId());
      publishState(order.orderId(), node.nodeId(), node.sequenceId(), false, actionStates(List.of(action), "RUNNING"));
      if ("pick".equals(action.actionType())) handlePick(action, node, epoch);
      else if ("drop".equals(action.actionType())) handleDrop(action, node, epoch);
      else if ("dock".equals(action.actionType())) handleDock(action, epoch);
      else controlledSleep(350, epoch);
      telemetry("ACTION_FINISHED", "type=" + action.actionType() + " node=" + node.nodeId() + " order=" + order.orderId());
    }
  }

  private void handlePick(Vda5050.Action action, Vda5050.Node node, long epoch) throws Exception {
    String loadId = stringParameter(action, "loadId");
    alignForHandling(action, epoch);
    moveFork(numberParameter(action, "targetHeight", .15), forkExtension, "RAISING", null, epoch);
    moveFork(forkHeight, .62, "INSERTING", null, epoch);
    carriedLoadId = loadId;
    moveFork(forkHeight + .12, forkExtension, "LIFTING", loadId, epoch);
    moveFork(forkHeight, 0, "RETRACTING", loadId, epoch);
    driveTo(node.nodePosition().x(), node.nodePosition().y(), dockingSpeed, epoch);
    moveFork(.25, 0, "LOWERING", loadId, epoch);
    handlingPhase = "COMPLETE";
    publishHandling();
  }

  private void handleDrop(Vda5050.Action action, Vda5050.Node node, long epoch) throws Exception {
    String loadId = stringParameter(action, "loadId");
    alignForHandling(action, epoch);
    double target = numberParameter(action, "targetHeight", .15) + .12;
    moveFork(target, forkExtension, "RAISING", loadId, epoch);
    moveFork(forkHeight, .62, "INSERTING", loadId, epoch);
    moveFork(Math.max(0, target - .12), forkExtension, "LOWERING", loadId, epoch);
    carriedLoadId = null;
    publishHandling();
    moveFork(forkHeight, 0, "RETRACTING", null, epoch);
    driveTo(node.nodePosition().x(), node.nodePosition().y(), dockingSpeed, epoch);
    moveFork(0, 0, "LOWERING", null, epoch);
    handlingPhase = "COMPLETE";
    publishHandling();
  }

  private void handleDock(Vda5050.Action action, long epoch) throws Exception {
    handlingPhase = "DOCKING";
    currentStationId = stringParameter(action, "stationId");
    publishHandling();
    turnTo(numberParameter(action, "targetTheta", position.theta()), epoch);
    charging = true;
    handlingPhase = "CHARGING";
    publishHandling();
    publishState("", currentStationId, 0, false, List.of());
  }

  private void alignForHandling(Vda5050.Action action, long epoch) throws Exception {
    handlingPhase = "ALIGNING";
    publishHandling();
    driveTo(numberParameter(action, "targetX", position.x()), numberParameter(action, "targetZ", position.y()), dockingSpeed, epoch);
    turnTo(numberParameter(action, "targetTheta", position.theta()), epoch);
  }

  private void moveFork(double targetHeight, double targetExtension, String phase, String loadId, long epoch) throws Exception {
    handlingPhase = phase;
    double duration = Math.max(Math.abs(targetHeight - forkHeight) / .75, Math.abs(targetExtension - forkExtension) / .55);
    int steps = Math.max(1, (int) Math.ceil(duration / .05));
    double startHeight = forkHeight;
    double startExtension = forkExtension;
    for (int step = 1; step <= steps; step++) {
      awaitRunning(epoch);
      double progress = (double) step / steps;
      forkHeight = startHeight + (targetHeight - startHeight) * progress;
      forkExtension = startExtension + (targetExtension - startExtension) * progress;
      publishHandling(loadId);
      controlledSleep(50, epoch);
    }
  }

  private void control(MqttMessage message) {
    try {
      if (message.getPayload().length == 0) return;
      @SuppressWarnings("unchecked") Map<String, Object> value = mapper.readValue(message.getPayload(), Map.class);
      String command = String.valueOf(value.get("command"));
      long epoch = ((Number) value.getOrDefault("epoch", simulationEpoch.get())).longValue();
      timeScale = ((Number) value.getOrDefault("timeScale", timeScale)).intValue();
      telemetry("CONTROL", "command=" + command + " epoch=" + epoch);
      if ("PAUSE".equals(command)) paused = true;
      if ("RESUME".equals(command)) { simulationEpoch.set(epoch); paused = false; }
      if ("SET_TIME_SCALE".equals(command)) timeScale = ((Number) value.getOrDefault("timeScale", 2)).intValue();
      if ("SYNC".equals(command) && activeOrder == null) {
        battery = ((Number) value.getOrDefault("battery", battery)).doubleValue();
        charging = Boolean.TRUE.equals(value.get("charging"));
        handlingPhase = String.valueOf(value.getOrDefault("handlingPhase", charging ? "CHARGING" : "IDLE"));
        currentStationId = value.get("stationId") == null ? null : String.valueOf(value.get("stationId"));
        position = new Vda5050.Position(((Number) value.getOrDefault("x", position.x())).doubleValue(),
            ((Number) value.getOrDefault("z", position.y())).doubleValue(), ((Number) value.getOrDefault("theta", position.theta())).doubleValue(), "linz", true);
        publishVisualization(0);
        publishState("", "", 0, false, List.of());
        initialSync.countDown();
      }
      if ("RESET".equals(command)) {
        simulationEpoch.set(epoch);
        paused = false;
        activeOrder = null;
        completedOrders.clear();
        battery = ((Number) value.getOrDefault("battery", 82)).doubleValue();
        velocity = 0;
        forkHeight = 0;
        forkExtension = 0;
        handlingPhase = String.valueOf(value.getOrDefault("handlingPhase", "CHARGING"));
        carriedLoadId = null;
        currentStationId = String.valueOf(value.getOrDefault("stationId", "PARK-01"));
        charging = Boolean.TRUE.equals(value.getOrDefault("charging", true));
        position = new Vda5050.Position(((Number) value.getOrDefault("x", 11)).doubleValue(),
            ((Number) value.getOrDefault("z", -6)).doubleValue(), ((Number) value.getOrDefault("theta", 0)).doubleValue(), "linz", true);
        publishHandling();
        publishVisualization(0);
        telemetry("RESET_POSE", "x=" + position.x() + " z=" + position.y());
      }
    } catch (Exception exception) {
      System.err.println("Rejected control: " + exception.getMessage());
    }
  }

  private void instantActions(MqttMessage message) {
    try {
      String json = new String(message.getPayload(), StandardCharsets.UTF_8);
      validator.validate("instantActions", json);
      Vda5050.InstantActions request = mapper.readValue(json, Vda5050.InstantActions.class);
      var states = new java.util.ArrayList<Map<String, Object>>();
      for (Vda5050.Action action : request.actions()) {
        if ("startPause".equals(action.actionType())) paused = true;
        else if ("stopPause".equals(action.actionType())) paused = false;
        else if ("cancelOrder".equals(action.actionType())) cancelRequested = true;
        else {
          publishState(activeOrder == null ? "" : activeOrder, lastNodeId, lastNodeSequenceId, false, List.of());
          continue;
        }
        states.add(Map.of("actionId", action.actionId(), "actionType", action.actionType(), "actionStatus", "FINISHED"));
      }
      instantActionStates = List.copyOf(states);
      publishState(activeOrder == null ? "" : activeOrder, lastNodeId, lastNodeSequenceId, false, List.of());
    } catch (Exception exception) {
      System.err.println("Rejected instant actions: " + exception.getMessage());
    }
  }

  private void awaitRunning(long epoch) throws InterruptedException {
    while (paused && epoch == simulationEpoch.get()) Thread.sleep(50);
    if (epoch != simulationEpoch.get()) throw new InterruptedException("Simulation reset");
    if (cancelRequested) throw new InterruptedException("Order cancelled");
  }

  private void controlledSleep(long milliseconds, long epoch) throws InterruptedException {
    long remaining = Math.max(1, Math.round((double) milliseconds / Math.max(1, timeScale)));
    while (remaining > 0) {
      awaitRunning(epoch);
      long slice = Math.min(remaining, 50);
      Thread.sleep(slice);
      remaining -= slice;
    }
  }

  private List<Map<String, Object>> actionStates(List<Vda5050.Action> actions, String status) {
    return actions.stream().map(action -> Map.<String, Object>of("actionId", action.actionId(), "actionType", action.actionType(), "actionStatus", status)).toList();
  }

  private void publishState(String orderId, String lastNode, long sequence, boolean driving, List<Map<String, Object>> actions) throws Exception {
    Vda5050.Order order = activeOrderPayload.get();
    List<Map<String, Object>> nodeStates = order == null ? List.of() : order.nodes().stream()
        .filter(node -> node.sequenceId() > sequence)
        .map(node -> Map.<String, Object>of("nodeId", node.nodeId(), "sequenceId", node.sequenceId(), "released", node.released()))
        .toList();
    List<Map<String, Object>> edgeStates = order == null ? List.of() : order.edges().stream()
        .filter(edge -> edge.sequenceId() > sequence)
        .map(edge -> Map.<String, Object>of("edgeId", edge.edgeId(), "sequenceId", edge.sequenceId(), "released", edge.released()))
        .toList();
    boolean newBaseRequest = order != null && nodeStates.stream().anyMatch(node -> Boolean.FALSE.equals(node.get("released")))
        && nodeStates.stream().noneMatch(node -> Boolean.TRUE.equals(node.get("released")));
    Vda5050.State state = new Vda5050.State(stateHeader.incrementAndGet(), Vda5050.now(), Vda5050.VERSION,
        Vda5050.MANUFACTURER, serialNumber, orderId, order == null ? 0 : order.orderUpdateId(), lastNode, sequence,
        driving, paused, newBaseRequest, "AUTOMATIC", position, new Vda5050.PowerSupply(battery, charging), nodeStates,
        edgeStates, actions, instantActionStates, List.of(), new Vda5050.SafetyState("NONE", false));
    publish("state", state, 0, false);
  }

  private void publishVisualization(double velocity) {
    this.velocity = velocity;
    visualizationDirty = true;
  }

  private void sendVisualization() throws Exception {
    Vda5050.Visualization visualization = new Vda5050.Visualization(visualizationHeader.incrementAndGet(), Vda5050.now(),
        Vda5050.VERSION, Vda5050.MANUFACTURER, serialNumber, stateHeader.get(), position,
        new Vda5050.Velocity(velocity, 0, 0));
    publish("visualization", visualization, 0, false);
  }

  private void publishHandling() { publishHandling(carriedLoadId); }

  private void publishHandling(String loadId) {
    telemetryLoadId = loadId;
    handlingDirty = true;
  }

  private void sendHandling() throws Exception {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("timestamp", Vda5050.now());
    value.put("phase", handlingPhase);
    value.put("forkHeight", forkHeight);
    value.put("forkExtension", forkExtension);
    value.put("loadId", telemetryLoadId);
    value.put("stationId", currentStationId);
    client.publish(Vda5050.topicPrefix(serialNumber) + "/handling", mapper.writeValueAsBytes(value), 0, false);
  }

  private void flushTelemetry() {
    try {
      if (visualizationDirty) {
        visualizationDirty = false;
        sendVisualization();
      }
      if (handlingDirty) {
        handlingDirty = false;
        sendHandling();
      }
    } catch (Exception exception) {
      System.err.println("Telemetry publish failed: " + exception.getMessage());
    }
  }

  private void chargeTick() {
    try {
      if (!charging || paused || activeOrder != null) return;
      battery = Math.min(100, battery + (5d / 60d) * timeScale);
      if (battery >= 100) {
        charging = false;
        handlingPhase = "PARKED";
      } else handlingPhase = "CHARGING";
      publishHandling();
      publishState("", currentStationId == null ? "" : currentStationId, 0, false, List.of());
    } catch (Exception exception) {
      System.err.println("Charging telemetry failed: " + exception.getMessage());
    }
  }

  private static double normalize(double angle) { return Math.atan2(Math.sin(angle), Math.cos(angle)); }

  private static String stringParameter(Vda5050.Action action, String key) {
    return action.actionParameters().stream().filter(parameter -> key.equals(parameter.key()))
        .map(parameter -> String.valueOf(parameter.value())).findFirst().orElse(null);
  }

  private static double numberParameter(Vda5050.Action action, String key, double fallback) {
    return action.actionParameters().stream().filter(parameter -> key.equals(parameter.key())).map(Vda5050.ActionParameter::value)
        .filter(Number.class::isInstance).map(Number.class::cast).map(Number::doubleValue).findFirst().orElse(fallback);
  }

  private Vda5050.Connection connection(String state) {
    return new Vda5050.Connection(stateHeader.incrementAndGet(), Vda5050.now(), Vda5050.VERSION,
        Vda5050.MANUFACTURER, serialNumber, state);
  }

  private void publish(String topic, Object value, int qos, boolean retained) throws Exception {
    validator.validate(topic, value);
    client.publish(Vda5050.topicPrefix(serialNumber) + "/" + topic, bytes(value), qos, retained);
  }

  private byte[] bytes(Object value) {
    try { return mapper.writeValueAsBytes(value); } catch (Exception exception) { throw new IllegalStateException(exception); }
  }

  private static void telemetry(String event, String details) {
    System.out.println("ANIMATION " + java.time.Instant.now() + " " + event + " " + details);
  }

  @PreDestroy
  void stop() throws Exception {
    executor.shutdownNow();
    mqttCallbacks.shutdownNow();
    charger.shutdownNow();
    telemetryPublisher.shutdownNow();
    if (client.isConnected()) {
      publish("connection", connection("OFFLINE"), 1, true);
      client.disconnect();
    }
    client.close();
  }
}
