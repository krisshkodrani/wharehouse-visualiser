package com.example.warehouse.simulator;

import com.example.warehouse.vda.Vda5050;
import com.example.warehouse.vda.VdaSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class AgvSimulator {
  private final ObjectMapper mapper;
  private final VdaSchemaValidator validator;
  private final MqttClient client;
  private final MqttConnectOptions options;
  private final double speed;
  private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("agv-motion").factory());
  private final Set<String> completedOrders = Collections.newSetFromMap(new ConcurrentHashMap<>());
  private final AtomicLong stateHeader = new AtomicLong();
  private final AtomicLong visualizationHeader = new AtomicLong();
  private final AtomicLong simulationEpoch = new AtomicLong(1);
  private volatile String activeOrder;
  private volatile boolean paused;
  private volatile double battery = 82;
  private volatile Vda5050.Position position = new Vda5050.Position(17, -12, 0, "linz", true);

  AgvSimulator(ObjectMapper mapper,
      @Value("${warehouse.mqtt-url}") String url,
      @Value("${warehouse.mqtt-user}") String user,
      @Value("${warehouse.mqtt-password}") String password,
      @Value("${warehouse.speed:4.0}") double speed) throws Exception {
    this.mapper = mapper; this.validator = new VdaSchemaValidator(mapper); this.speed = speed;
    this.client = new MqttClient(url, "agv-FL-01", new MemoryPersistence());
    this.options = new MqttConnectOptions();
    options.setCleanSession(true);
    options.setAutomaticReconnect(true);
    options.setUserName(user);
    options.setPassword(password.toCharArray());
    options.setConnectionTimeout(10);
    options.setWill(Vda5050.TOPIC_PREFIX + "/connection", bytes(connection("CONNECTIONBROKEN")), 1, true);
  }

  @PostConstruct
  void start() throws Exception {
    client.connect(options);
    publish("connection", connection("ONLINE"), 1, true);
    client.subscribe(Vda5050.TOPIC_PREFIX + "/order", 0, (topic, message) -> receive(message));
    client.subscribe(Vda5050.TOPIC_PREFIX + "/control", 1, (topic, message) -> control(message));
    publishState("", "INBOUND", 0, false, List.of());
  }

  private void receive(MqttMessage message) {
    try {
      String json = new String(message.getPayload(), StandardCharsets.UTF_8);
      validator.validate("order", json);
      Vda5050.Order order = mapper.readValue(json, Vda5050.Order.class);
      if (completedOrders.contains(order.orderId()) || order.orderId().equals(activeOrder)) return;
      if (activeOrder != null) return;
      activeOrder = order.orderId();
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
      if (Math.hypot(position.x() - first.nodePosition().x(), position.y() - first.nodePosition().y()) > .01) {
        Vda5050.Node current = new Vda5050.Node("CURRENT", 0, true,
            new Vda5050.NodePosition(position.x(), position.y(), "linz", .25), List.of());
        drive(current, first, epoch);
      }
      position = new Vda5050.Position(first.nodePosition().x(), first.nodePosition().y(), position.theta(), "linz", true);
      executeActions(first.actions(), order, first, epoch);
      publishState(order.orderId(), first.nodeId(), first.sequenceId(), true, actionStates(first.actions(), "FINISHED"));
      for (int index = 1; index < nodes.size(); index++) {
        Vda5050.Node from = nodes.get(index - 1);
        Vda5050.Node to = nodes.get(index);
        drive(from, to, epoch);
        executeActions(to.actions(), order, to, epoch);
        boolean driving = index < nodes.size() - 1;
        publishState(order.orderId(), to.nodeId(), to.sequenceId(), driving, actionStates(to.actions(), "FINISHED"));
      }
      completedOrders.add(order.orderId());
      telemetry("ORDER_COMPLETED", "order=" + order.orderId() + " x=" + position.x() + " z=" + position.y());
    } catch (Exception exception) {
      System.err.println("Order failed: " + exception.getMessage());
    } finally {
      if (order.orderId().equals(activeOrder)) activeOrder = null;
    }
  }

  private void drive(Vda5050.Node from, Vda5050.Node to, long epoch) throws Exception {
    double startX = from.nodePosition().x();
    double startY = from.nodePosition().y();
    double endX = to.nodePosition().x();
    double endY = to.nodePosition().y();
    double distance = Math.hypot(endX - startX, endY - startY);
    double theta = Math.atan2(endY - startY, endX - startX);
    telemetry("LEG_STARTED", "from=" + from.nodeId() + " to=" + to.nodeId() + " distance=" + String.format(java.util.Locale.ROOT, "%.2f", distance));
    int steps = Math.max(1, (int) Math.ceil(distance / speed * 10));
    for (int step = 1; step <= steps; step++) {
      awaitRunning(epoch);
      double progress = (double) step / steps;
      position = new Vda5050.Position(startX + (endX - startX) * progress, startY + (endY - startY) * progress, theta, "linz", true);
      battery = Math.max(0, battery - distance / steps * .015);
      publishVisualization(speed);
      controlledSleep(100, epoch);
    }
    telemetry("LEG_ARRIVED", "node=" + to.nodeId() + " x=" + endX + " z=" + endY);
  }

  private void executeActions(List<Vda5050.Action> actions, Vda5050.Order order, Vda5050.Node node, long epoch) throws Exception {
    for (Vda5050.Action action : actions) {
      awaitRunning(epoch);
      telemetry("ACTION_STARTED", "type=" + action.actionType() + " node=" + node.nodeId() + " order=" + order.orderId());
      publishState(order.orderId(), node.nodeId(), node.sequenceId(), false, actionStates(List.of(action), "RUNNING"));
      controlledSleep(350, epoch);
      telemetry("ACTION_FINISHED", "type=" + action.actionType() + " node=" + node.nodeId() + " order=" + order.orderId());
    }
  }

  private void control(MqttMessage message) {
    try {
      if (message.getPayload().length == 0) return;
      @SuppressWarnings("unchecked") Map<String, Object> value = mapper.readValue(message.getPayload(), Map.class);
      String command = String.valueOf(value.get("command"));
      long epoch = ((Number) value.getOrDefault("epoch", simulationEpoch.get())).longValue();
      telemetry("CONTROL", "command=" + command + " epoch=" + epoch);
      if ("PAUSE".equals(command)) paused = true;
      if ("RESUME".equals(command)) { simulationEpoch.set(epoch); paused = false; }
      if ("RESET".equals(command)) {
        simulationEpoch.set(epoch);
        paused = false;
        activeOrder = null;
        completedOrders.clear();
        battery = ((Number) value.getOrDefault("battery", 82)).doubleValue();
        position = new Vda5050.Position(((Number) value.getOrDefault("x", 17)).doubleValue(),
            ((Number) value.getOrDefault("z", -12)).doubleValue(), ((Number) value.getOrDefault("theta", 0)).doubleValue(), "linz", true);
        telemetry("RESET_POSE", "x=" + position.x() + " z=" + position.y());
      }
    } catch (Exception exception) {
      System.err.println("Rejected control: " + exception.getMessage());
    }
  }

  private void awaitRunning(long epoch) throws InterruptedException {
    while (paused && epoch == simulationEpoch.get()) Thread.sleep(50);
    if (epoch != simulationEpoch.get()) throw new InterruptedException("Simulation reset");
  }

  private void controlledSleep(long milliseconds, long epoch) throws InterruptedException {
    long remaining = milliseconds;
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
    Vda5050.State state = new Vda5050.State(stateHeader.incrementAndGet(), Vda5050.now(), Vda5050.VERSION,
        Vda5050.MANUFACTURER, Vda5050.SERIAL_NUMBER, orderId, 0, lastNode, sequence, driving, false, "AUTOMATIC",
        position, new Vda5050.PowerSupply(battery, false), List.of(), List.of(), actions, List.of());
    publish("state", state, 0, false);
  }

  private void publishVisualization(double velocity) throws Exception {
    Vda5050.Visualization visualization = new Vda5050.Visualization(visualizationHeader.incrementAndGet(), Vda5050.now(),
        Vda5050.VERSION, Vda5050.MANUFACTURER, Vda5050.SERIAL_NUMBER, position, velocity);
    publish("visualization", visualization, 0, false);
  }

  private Vda5050.Connection connection(String state) {
    return new Vda5050.Connection(stateHeader.incrementAndGet(), Vda5050.now(), Vda5050.VERSION,
        Vda5050.MANUFACTURER, Vda5050.SERIAL_NUMBER, state);
  }

  private void publish(String topic, Object value, int qos, boolean retained) throws Exception {
    validator.validate(topic, value);
    client.publish(Vda5050.TOPIC_PREFIX + "/" + topic, bytes(value), qos, retained);
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
    if (client.isConnected()) {
      publish("connection", connection("OFFLINE"), 1, true);
      client.disconnect();
    }
    client.close();
  }
}
