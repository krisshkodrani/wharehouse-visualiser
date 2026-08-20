package com.example.warehouse.simulator;

import com.example.warehouse.vda.Vda5050;
import com.example.warehouse.vda.VdaSchemaValidator;
import com.example.warehouse.simulator.vehicle.BatteryModel;
import com.example.warehouse.simulator.vehicle.MotionController;
import com.example.warehouse.simulator.vehicle.ForkController;
import com.example.warehouse.simulator.vehicle.VehicleState;
import com.example.warehouse.simulator.runtime.SimulationClock;
import com.example.warehouse.simulator.runtime.SimulationControl;
import com.example.warehouse.simulator.execution.ActionExecutor;
import com.example.warehouse.simulator.execution.NodeExecutor;
import com.example.warehouse.simulator.execution.OrderExecutor;
import com.example.warehouse.simulator.mqtt.SimulatorMqttClient;
import com.example.warehouse.simulator.mqtt.TopicSubscriptions;
import com.example.warehouse.simulator.config.SimulatorProperties;
import com.example.warehouse.simulator.vda5050.StatePublisher;
import com.example.warehouse.simulator.vda5050.VisualizationPublisher;
import com.example.warehouse.simulator.vda5050.ConnectionPublisher;
import com.example.warehouse.simulator.vda5050.OrderHandler;
import com.example.warehouse.simulator.vda5050.InstantActionHandler;
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
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class AgvSimulator implements ActionExecutor.Context, NodeExecutor.Context, OrderExecutor.Context {
  private final String serialNumber;
  private final ObjectMapper mapper;
  private final VdaSchemaValidator validator;
  private final SimulatorMqttClient mqtt;
  private final TopicSubscriptions subscriptions;
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
  private final AtomicLong instantActionHeader = new AtomicLong();
  private final SimulationClock clock = new SimulationClock();
  private final SimulationControl simulationControl;
  private final ActionExecutor actionExecutor = new ActionExecutor();
  private final NodeExecutor nodeExecutor = new NodeExecutor();
  private final OrderExecutor orderExecutor = new OrderExecutor();
  private final CountDownLatch initialSync = new CountDownLatch(1);
  private volatile String activeOrder;
  private final AtomicReference<Vda5050.Order> activeOrderPayload = new AtomicReference<>();
  private volatile boolean cancelRequested;
  /** Set when a newly arrived order supersedes the one in flight. Distinct from
   * {@link #cancelRequested}: a preempted order is abandoned, not cancelled, so it must not
   * drop the load or report ORDER_CANCELLED to master control. */
  private volatile boolean preemptRequested;
  /** The superseding order, held until the running one releases so two executions can never
   * fight over the pose. */
  private final AtomicReference<Vda5050.Order> pendingOrder = new AtomicReference<>();
  private volatile String lastNodeId = "";
  private volatile long lastNodeSequenceId;
  private volatile List<Map<String, Object>> instantActionStates = List.of();
  private final VehicleState vehicle = new VehicleState();
  private final StatePublisher statePublisher;
  private final VisualizationPublisher visualizationPublisher;
  private final ConnectionPublisher connectionPublisher;
  private final OrderHandler orderHandler;
  private final InstantActionHandler instantActionHandler;
  private final ForkController fork;
  private final double chargePerMinute;
  private final int telemetryIntervalMillis;
  private volatile boolean visualizationDirty;
  private volatile boolean handlingDirty;
  private volatile String telemetryLoadId;

  @Autowired
  AgvSimulator(ObjectMapper mapper, SimulatorProperties properties) throws Exception {
    this(mapper, properties.vehicleId(), properties.mqttUrl(), properties.mqttUser(), properties.mqttPassword(),
        properties.speed(), properties.loadedSpeed(), properties.dockingSpeed(), properties.acceleration(),
        properties.braking(), properties.batteryConsumptionPerMetre(), properties.forkLiftSpeed(),
        properties.forkExtensionSpeed(), properties.chargePerMinute(), properties.telemetryIntervalMillis());
  }

  AgvSimulator(ObjectMapper mapper, String serialNumber, String url, String user, String password,
      double emptySpeed, double loadedSpeed, double dockingSpeed, double acceleration, double braking,
      double batteryConsumptionPerMetre) throws Exception {
    this(mapper, serialNumber, url, user, password, emptySpeed, loadedSpeed, dockingSpeed,
        acceleration, braking, batteryConsumptionPerMetre, .75, .55, 5, 50);
  }

  AgvSimulator(ObjectMapper mapper, String serialNumber, String url, String user, String password,
      double emptySpeed, double loadedSpeed, double dockingSpeed, double acceleration, double braking,
      double batteryConsumptionPerMetre, double forkLiftSpeed, double forkExtensionSpeed,
      double chargePerMinute, int telemetryIntervalMillis) throws Exception {
    this.serialNumber = serialNumber;
    this.mapper = mapper; this.validator = new VdaSchemaValidator(mapper); this.emptySpeed = emptySpeed;
    this.simulationControl = new SimulationControl(mapper, clock);
    this.loadedSpeed = loadedSpeed; this.dockingSpeed = dockingSpeed; this.acceleration = acceleration; this.braking = braking;
    this.fork = new ForkController(forkLiftSpeed, forkExtensionSpeed, telemetryIntervalMillis);
    this.chargePerMinute = chargePerMinute;
    this.telemetryIntervalMillis = telemetryIntervalMillis;
    BatteryModel.consume(100, 0, batteryConsumptionPerMetre);
    this.batteryConsumptionPerMetre = batteryConsumptionPerMetre;
    this.subscriptions = TopicSubscriptions.forVehicle(serialNumber);
    this.mqtt = new SimulatorMqttClient(url, "agv-" + serialNumber, user, password,
        Vda5050.topicPrefix(serialNumber) + "/connection",
        bytes(ConnectionPublisher.message(serialNumber, stateHeader, "CONNECTION_BROKEN")));
    this.statePublisher = new StatePublisher(serialNumber, mapper, validator, mqtt, stateHeader);
    this.visualizationPublisher = new VisualizationPublisher(serialNumber, mapper, validator, mqtt);
    this.connectionPublisher = new ConnectionPublisher(serialNumber, mapper, validator, mqtt, stateHeader);
    this.orderHandler = new OrderHandler(mapper, validator);
    this.instantActionHandler = new InstantActionHandler(mapper, validator);
  }

  @PostConstruct
  void start() throws Exception {
    mqtt.setCallback(new MqttCallbackExtended() {
      @Override public void connectComplete(boolean reconnect, String serverUri) {
        if (!reconnect) return;
        try {
          subscribeTopics();
          connectionPublisher.publish("ONLINE");
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
    mqtt.connect();
    subscribeTopics();
    connectionPublisher.publish("ONLINE");
    initialSync.await(2, TimeUnit.SECONDS);
    publishState("", vehicle.stationId() == null ? "" : vehicle.stationId(), 0, false, List.of());
    publishVisualization(0);
    publishHandling();
    telemetryPublisher.scheduleWithFixedDelay(
        this::flushTelemetry, 0, telemetryIntervalMillis, TimeUnit.MILLISECONDS);
    charger.scheduleAtFixedRate(this::chargeTick, 1, 1, TimeUnit.SECONDS);
  }

  private void subscribeTopics() throws Exception {
    mqtt.subscribe(subscriptions.topics(), subscriptions.qos());
    telemetry("MQTT_SUBSCRIBED", "topics=order,instantActions,control");
  }

  private void receive(MqttMessage message) {
    try {
      Vda5050.Order order = orderHandler.decode(message);
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
      if (activeOrder != null) {
        // This used to `return`, dropping the order on the floor with no log and no
        // rejection: master control had already recorded it as dispatched, so the task sat
        // in DISPATCHED for ever and its load was stranded. Master control only ever hands
        // out an order when it has claimed a vehicle with no task_id, so anything active
        // here is a housekeeping move (the park drive) and is legitimately preemptable.
        // The order is queued rather than started so two executions cannot fight over the
        // pose; the running one picks it up as it unwinds.
        pendingOrder.set(order);
        preemptRequested = true;
        telemetry("ORDER_PREEMPTS_ACTIVE", "order=" + order.orderId() + " supersedes=" + activeOrder);
        return;
      }
      accept(order);
    } catch (Exception exception) {
      System.err.println("Rejected order: " + exception.getMessage());
    }
  }

  private void execute(Vda5050.Order order, long epoch) {
    orderExecutor.execute(order, epoch, this);
  }

  @Override
  public double theta() { return vehicle.pose().theta(); }

  @Override
  public void pose(double x, double z, double theta) { vehicle.pose(x, z, theta); }

  @Override
  public Vda5050.Order currentOrder() { return activeOrderPayload.get(); }

  @Override
  public void visit(Vda5050.Node node) {
    lastNodeId = node.nodeId();
    lastNodeSequenceId = node.sequenceId();
  }

  @Override
  public void markComplete(String orderId) {
    completedOrders.add(orderId);
    activeOrder = null;
  }

  @Override
  public void publishFinished(Vda5050.Order order, Vda5050.Node node, boolean driving) throws Exception {
    publishState(order.orderId(), node.nodeId(), node.sequenceId(), driving,
        actionStates(node.actions(), "FINISHED"));
  }

  @Override
  public void stopVisualization() { publishVisualization(0); }

  @Override
  public void orderCompleted(Vda5050.Order order) {
    telemetry("ORDER_COMPLETED", "order=" + order.orderId()
        + " x=" + vehicle.pose().x() + " z=" + vehicle.pose().z());
  }

  @Override
  public void orderFailed(Vda5050.Order order, Exception exception) {
    if (cancelRequested) {
      vehicle.velocity(0);
      fork.reset("IDLE");
      publishHandling();
      publishVisualization(0);
      telemetry("ORDER_CANCELLED", "order=" + order.orderId());
    } else if (preemptRequested) {
      vehicle.velocity(0);
      telemetry("ORDER_PREEMPTED", "order=" + order.orderId());
    } else {
      System.err.println("Order failed: " + exception.getMessage());
    }
  }

  @Override
  public synchronized void cleanupOrder(Vda5050.Order order) {
    if (!order.orderId().equals(activeOrder)) return;
    activeOrder = null;
    activeOrderPayload.set(null);
    cancelRequested = false;
    preemptRequested = false;
    Vda5050.Order queued = pendingOrder.getAndSet(null);
    if (queued != null) accept(queued);
  }

  /** Begins an order that has already been validated and cleared for execution. */
  private void accept(Vda5050.Order order) {
    vehicle.charging(false);
    fork.phase("IDLE");
    vehicle.stationId(null);
    activeOrder = order.orderId();
    activeOrderPayload.set(order);
    cancelRequested = false;
    // Instant actions belong to the order they were issued against. Reporting a
    // finished cancelOrder alongside a freshly accepted order tells the master
    // control that this order was cancelled too.
    instantActionStates = List.of();
    publishHandling();
    telemetry("ORDER_ACCEPTED", "order=" + order.orderId() + " nodes=" + order.nodes().size());
    long epoch = clock.epoch();
    executor.submit(() -> execute(order, epoch));
  }

  @Override
  public void awaitReleased(int nodeIndex, long epoch) throws Exception {
    while (true) {
      awaitRunning(epoch);
      Vda5050.Order current = activeOrderPayload.get();
      if (current != null && nodeIndex < current.nodes().size() && current.nodes().get(nodeIndex).released()) return;
      publishState(activeOrder == null ? "" : activeOrder, lastNodeId, lastNodeSequenceId, false, List.of());
      controlledSleep(100, epoch);
    }
  }

  @Override
  public void drive(Vda5050.Node from, Vda5050.Node to, long epoch) throws Exception {
    double limit = to.actions().isEmpty() ? (fork.carriedLoadId() == null ? emptySpeed : loadedSpeed) : dockingSpeed;
    nodeExecutor.execute(from, to, limit, epoch, this);
  }

  @Override
  public void driveTo(double endX, double endY, double speedLimit, long epoch) throws Exception {
    double startX = vehicle.pose().x();
    double startY = vehicle.pose().z();
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
      currentSpeed = MotionController.nextSpeed(currentSpeed, remaining, speedLimit, acceleration, braking, dt);
      double step = Math.min(remaining, currentSpeed * dt);
      travelled += step;
      double progress = travelled / distance;
      vehicle.pose(startX + (endX - startX) * progress, startY + (endY - startY) * progress, theta);
      vehicle.velocity(currentSpeed);
      vehicle.battery(BatteryModel.consume(vehicle.battery(), step, batteryConsumptionPerMetre));
      publishVisualization(currentSpeed);
      controlledSleep(telemetryIntervalMillis, epoch);
    }
    vehicle.pose(endX, endY, theta);
    vehicle.velocity(0);
    publishVisualization(0);
  }

  @Override
  public double x() { return vehicle.pose().x(); }

  @Override
  public double z() { return vehicle.pose().z(); }

  @Override
  public void legStarted(Vda5050.Node from, Vda5050.Node to, double distance) {
    telemetry("LEG_STARTED", "from=" + from.nodeId() + " to=" + to.nodeId()
        + " distance=" + String.format(java.util.Locale.ROOT, "%.2f", distance));
  }

  @Override
  public void legArrived(Vda5050.Node node) {
    telemetry("LEG_ARRIVED", "node=" + node.nodeId()
        + " x=" + node.nodePosition().x() + " z=" + node.nodePosition().y());
  }

  private void turnTo(double target, long epoch) throws Exception {
    double delta = normalize(target - vehicle.pose().theta());
    final double yawPerTick = Math.toRadians(60) * .05;
    while (Math.abs(delta) > .01) {
      awaitRunning(epoch);
      double step = Math.copySign(Math.min(Math.abs(delta), yawPerTick), delta);
      vehicle.pose(vehicle.pose().x(), vehicle.pose().z(), normalize(vehicle.pose().theta() + step));
      vehicle.velocity(0);
      publishVisualization(0);
      controlledSleep(telemetryIntervalMillis, epoch);
      delta = normalize(target - vehicle.pose().theta());
    }
  }

  @Override
  public void executeActions(List<Vda5050.Action> actions, Vda5050.Order order, Vda5050.Node node, long epoch) throws Exception {
    actionExecutor.execute(actions, order, node, epoch, this);
  }

  @Override
  public void actionStarted(Vda5050.Action action, Vda5050.Order order, Vda5050.Node node) {
    telemetry("ACTION_STARTED",
        "type=" + action.actionType() + " node=" + node.nodeId() + " order=" + order.orderId());
  }

  @Override
  public void publishRunning(Vda5050.Action action, Vda5050.Order order, Vda5050.Node node) throws Exception {
    publishState(order.orderId(), node.nodeId(), node.sequenceId(), false,
        actionStates(List.of(action), "RUNNING"));
  }

  @Override
  public void waitForUnsupported(long epoch) throws Exception {
    controlledSleep(350, epoch);
  }

  @Override
  public void actionFinished(Vda5050.Action action, Vda5050.Order order, Vda5050.Node node) {
    telemetry("ACTION_FINISHED",
        "type=" + action.actionType() + " node=" + node.nodeId() + " order=" + order.orderId());
  }

  @Override
  public void pick(Vda5050.Action action, Vda5050.Node node, long epoch) throws Exception {
    String loadId = stringParameter(action, "loadId");
    alignForHandling(action, epoch);
    moveFork(numberParameter(action, "targetHeight", .15), fork.extension(), "RAISING", null, epoch);
    moveFork(fork.height(), .62, "INSERTING", null, epoch);
    fork.carriedLoad(loadId);
    moveFork(fork.height() + .12, fork.extension(), "LIFTING", loadId, epoch);
    moveFork(fork.height(), 0, "RETRACTING", loadId, epoch);
    driveTo(node.nodePosition().x(), node.nodePosition().y(), dockingSpeed, epoch);
    moveFork(.25, 0, "LOWERING", loadId, epoch);
    fork.phase("COMPLETE");
    publishHandling();
  }

  @Override
  public void drop(Vda5050.Action action, Vda5050.Node node, long epoch) throws Exception {
    String loadId = stringParameter(action, "loadId");
    alignForHandling(action, epoch);
    double target = numberParameter(action, "targetHeight", .15) + .12;
    moveFork(target, fork.extension(), "RAISING", loadId, epoch);
    moveFork(fork.height(), .62, "INSERTING", loadId, epoch);
    moveFork(Math.max(0, target - .12), fork.extension(), "LOWERING", loadId, epoch);
    fork.carriedLoad(null);
    publishHandling();
    moveFork(fork.height(), 0, "RETRACTING", null, epoch);
    driveTo(node.nodePosition().x(), node.nodePosition().y(), dockingSpeed, epoch);
    moveFork(0, 0, "LOWERING", null, epoch);
    fork.phase("COMPLETE");
    publishHandling();
  }

  @Override
  public void dock(Vda5050.Action action, long epoch) throws Exception {
    fork.phase("DOCKING");
    vehicle.stationId(stringParameter(action, "stationId"));
    publishHandling();
    turnTo(numberParameter(action, "targetTheta", vehicle.pose().theta()), epoch);
    vehicle.charging(true);
    fork.phase("CHARGING");
    publishHandling();
    publishState("", vehicle.stationId(), 0, false, List.of());
  }

  private void alignForHandling(Vda5050.Action action, long epoch) throws Exception {
    fork.phase("ALIGNING");
    publishHandling();
    driveTo(numberParameter(action, "targetX", vehicle.pose().x()), numberParameter(action, "targetZ", vehicle.pose().z()), dockingSpeed, epoch);
    turnTo(numberParameter(action, "targetTheta", vehicle.pose().theta()), epoch);
  }

  private void moveFork(double targetHeight, double targetExtension, String phase, String loadId, long epoch) throws Exception {
    fork.phase(phase);
    int steps = fork.stepsTo(targetHeight, targetExtension);
    double startHeight = fork.height();
    double startExtension = fork.extension();
    for (int step = 1; step <= steps; step++) {
      awaitRunning(epoch);
      double progress = (double) step / steps;
      fork.position(
          startHeight + (targetHeight - startHeight) * progress,
          startExtension + (targetExtension - startExtension) * progress);
      publishHandling(loadId);
      controlledSleep(telemetryIntervalMillis, epoch);
    }
  }

  private void control(MqttMessage message) {
    try {
      if (message.getPayload().length == 0) return;
      SimulationControl.Command control = simulationControl.apply(message);
      Map<String, Object> value = control.values();
      String command = control.name();
      long epoch = control.epoch();
      telemetry("CONTROL", "command=" + command + " epoch=" + epoch);
      if ("SYNC".equals(command) && activeOrder == null) {
        vehicle.battery(((Number) value.getOrDefault("battery", vehicle.battery())).doubleValue());
        vehicle.charging(Boolean.TRUE.equals(value.get("charging")));
        fork.phase(String.valueOf(value.getOrDefault("handlingPhase", vehicle.charging() ? "CHARGING" : "IDLE")));
        vehicle.stationId(value.get("stationId") == null ? null : String.valueOf(value.get("stationId")));
        vehicle.pose(((Number) value.getOrDefault("x", vehicle.pose().x())).doubleValue(),
            ((Number) value.getOrDefault("z", vehicle.pose().z())).doubleValue(),
            ((Number) value.getOrDefault("theta", vehicle.pose().theta())).doubleValue());
        publishVisualization(0);
        publishState("", "", 0, false, List.of());
        initialSync.countDown();
      }
      if ("RESET".equals(command)) {
        clock.reset(epoch);
        activeOrder = null;
        activeOrderPayload.set(null);
        completedOrders.clear();
        // A queued preemption belongs to the epoch that is being torn down.
        pendingOrder.set(null);
        cancelRequested = false;
        preemptRequested = false;
        instantActionStates = List.of();
        lastNodeId = "";
        lastNodeSequenceId = 0;
        fork.reset(String.valueOf(value.getOrDefault("handlingPhase", "CHARGING")));
        vehicle.reset(
            ((Number) value.getOrDefault("x", 11)).doubleValue(),
            ((Number) value.getOrDefault("z", -6)).doubleValue(),
            ((Number) value.getOrDefault("theta", 0)).doubleValue(),
            ((Number) value.getOrDefault("battery", 82)).doubleValue(),
            String.valueOf(value.getOrDefault("stationId", "PARK-01")),
            Boolean.TRUE.equals(value.getOrDefault("charging", true)));
        publishHandling();
        publishVisualization(0);
        telemetry("RESET_POSE", "x=" + vehicle.pose().x() + " z=" + vehicle.pose().z());
      }
    } catch (Exception exception) {
      System.err.println("Rejected control: " + exception.getMessage());
    }
  }

  private void instantActions(MqttMessage message) {
    try {
      Vda5050.InstantActions request = instantActionHandler.decode(message);
      var states = new java.util.ArrayList<Map<String, Object>>();
      for (Vda5050.Action action : request.actions()) {
        if ("startPause".equals(action.actionType())) clock.pause();
        else if ("stopPause".equals(action.actionType())) clock.resume(clock.epoch());
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

  @Override
  public void awaitRunning(long epoch) throws InterruptedException {
    clock.awaitRunning(epoch, () -> cancelRequested, () -> preemptRequested);
  }

  private void controlledSleep(long milliseconds, long epoch) throws InterruptedException {
    clock.sleep(milliseconds, epoch, () -> cancelRequested, () -> preemptRequested);
  }

  private List<Map<String, Object>> actionStates(List<Vda5050.Action> actions, String status) {
    return actions.stream().map(action -> Map.<String, Object>of("actionId", action.actionId(), "actionType", action.actionType(), "actionStatus", status)).toList();
  }

  private void publishState(String orderId, String lastNode, long sequence, boolean driving, List<Map<String, Object>> actions) throws Exception {
    statePublisher.publish(activeOrderPayload.get(), orderId, lastNode, sequence,
        driving, clock.paused(), vehicle, actions, instantActionStates);
  }

  private void publishVisualization(double velocity) {
    vehicle.velocity(velocity);
    visualizationDirty = true;
  }

  private void sendVisualization() throws Exception {
    visualizationPublisher.publish(vehicle, statePublisher.header());
  }

  private void publishHandling() { publishHandling(fork.carriedLoadId()); }

  private void publishHandling(String loadId) {
    telemetryLoadId = loadId;
    handlingDirty = true;
  }

  private void sendHandling() throws Exception {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("timestamp", Vda5050.now());
    value.put("phase", fork.phase());
    value.put("forkHeight", fork.height());
    value.put("forkExtension", fork.extension());
    value.put("loadId", telemetryLoadId);
    value.put("stationId", vehicle.stationId());
    mqtt.publish(Vda5050.topicPrefix(serialNumber) + "/handling", mapper.writeValueAsBytes(value), 0, false);
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
      if (!vehicle.charging() || clock.paused() || activeOrder != null) return;
      vehicle.battery(Math.min(
          100, vehicle.battery() + (chargePerMinute / 60d) * clock.timeScale()));
      if (vehicle.battery() >= 100) {
        vehicle.charging(false);
        fork.phase("PARKED");
      } else fork.phase("CHARGING");
      publishHandling();
      publishState("", vehicle.stationId() == null ? "" : vehicle.stationId(), 0, false, List.of());
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

  private void publish(String topic, Object value, int qos, boolean retained) throws Exception {
    validator.validate(topic, value);
    mqtt.publish(Vda5050.topicPrefix(serialNumber) + "/" + topic, bytes(value), qos, retained);
  }

  private byte[] bytes(Object value) {
    try { return mapper.writeValueAsBytes(value); } catch (Exception exception) { throw new IllegalStateException(exception); }
  }

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgvSimulator.class);

  /** The vehicle's side of the story, in the same vocabulary the backend uses.
   *
   * <p>This printed "ANIMATION <instant> <event> <details>" straight to stdout, which
   * carried no level, no correlation and no structure, so following one order across
   * the vehicle and the backend meant matching wall-clock timestamps by eye between
   * two container logs. Routing it through SLF4J with the event in MDC puts it in the
   * same ECS file as everything else, queryable by the same fields.
   *
   * <p>The "ANIMATION" prefix is kept because it is what makes the vehicle's own
   * narration easy to pick out of {@code docker compose logs simulator} by eye --
   * following one order that way is how the base/horizon stall was first spotted.
   * Nothing parses it, so it is a convenience rather than a contract. */
  private static void telemetry(String event, String details) {
    // Promote the order id out of the details text into its own field. Left in the
    // message it is greppable but not selectable, so `jq 'select(.taskId==…)'` over
    // both log files returned backend records only and silently under-reported the
    // vehicle's side of the same task -- which is the correlation this exists for.
    String taskId = orderIdFrom(details);
    try (org.slf4j.MDC.MDCCloseable ignored = org.slf4j.MDC.putCloseable("event", event);
        org.slf4j.MDC.MDCCloseable source = org.slf4j.MDC.putCloseable("source", "simulator")) {
      if (taskId == null) {
        log.info("ANIMATION {} {} {}", java.time.Instant.now(), event, details);
        return;
      }
      try (org.slf4j.MDC.MDCCloseable task = org.slf4j.MDC.putCloseable("taskId", taskId)) {
        log.info("ANIMATION {} {} {}", java.time.Instant.now(), event, details);
      }
    }
  }

  /** The backend names a transport task; the vehicle calls the same thing an order.
   * Every call site that has one already writes it as {@code order=<uuid>}. */
  private static final java.util.regex.Pattern ORDER_TOKEN =
      java.util.regex.Pattern.compile("\\border=([0-9a-fA-F-]{36})\\b");

  private static String orderIdFrom(String details) {
    if (details == null) return null;
    java.util.regex.Matcher matcher = ORDER_TOKEN.matcher(details);
    return matcher.find() ? matcher.group(1) : null;
  }

  @PreDestroy
  void stop() throws Exception {
    executor.shutdownNow();
    mqttCallbacks.shutdownNow();
    charger.shutdownNow();
    telemetryPublisher.shutdownNow();
    if (mqtt.isConnected()) {
      connectionPublisher.publish("OFFLINE");
      mqtt.disconnect();
    }
    mqtt.close();
  }
}
