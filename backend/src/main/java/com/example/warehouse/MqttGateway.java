package com.example.warehouse;

import com.example.warehouse.vda.Vda5050;
import com.example.warehouse.vda.VdaSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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
  private final VdaSchemaValidator validator;
  private final MqttClient client;
  private final MqttConnectOptions options;

  MqttGateway(ObjectMapper mapper, WarehouseStore store, JobExecutionService execution, EventPublisher events,
      @Value("${warehouse.mqtt-url}") String url,
      @Value("${warehouse.mqtt-user}") String user,
      @Value("${warehouse.mqtt-password}") String password) throws Exception {
    this.mapper = mapper; this.store = store; this.execution = execution; this.events = events;
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
      subscribe(Vda5050.TOPIC_PREFIX + "/visualization", this::onVisualization);
      subscribe(Vda5050.TOPIC_PREFIX + "/connection", this::onConnection);
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
      } catch (Exception exception) {
        store.failed(row.id());
        break;
      }
    }
  }

  void publishControl(String command, ApiModels.RuntimeView runtime) {
    if (!client.isConnected()) return;
    try {
      WarehouseStore.NodeRow home = store.locationPosition("INBOUND-01");
      String payload = mapper.writeValueAsString(java.util.Map.of(
          "command", command, "epoch", runtime.simulationEpoch(), "x", home.x(), "z", home.z(), "theta", 0, "battery", 82));
      client.publish(Vda5050.TOPIC_PREFIX + "/control", payload.getBytes(StandardCharsets.UTF_8), 1, false);
    } catch (Exception exception) {
      throw new IllegalStateException("Could not send simulator control", exception);
    }
  }

  private void subscribe(String topic, IMqttMessageListener listener) throws Exception {
    client.subscribe(topic, 0, listener);
  }

  private void onState(String topic, MqttMessage message) {
    try {
      String json = new String(message.getPayload(), StandardCharsets.UTF_8);
      validator.validate("state", json);
      Vda5050.State state = mapper.readValue(json, Vda5050.State.class);
      UUID jobId = uuid(state.orderId());
      if (jobId == null) return;
      if (hasAction(state, "pick", "RUNNING") || hasAction(state, "pick", "FINISHED")) execution.picked(jobId);
      else if (state.driving()) execution.executing(jobId);
      else if (state.actionStates().isEmpty() || state.actionStates().stream().allMatch(action -> "FINISHED".equals(action.get("actionStatus"))))
        execution.completeIfArrived(jobId, state.lastNodeId());
    } catch (Exception exception) {
      events.publish("AGV_MESSAGE_REJECTED", java.util.Map.of("topic", topic, "error", exception.getMessage()));
    }
  }

  private void onVisualization(String topic, MqttMessage message) {
    try {
      String json = new String(message.getPayload(), StandardCharsets.UTF_8);
      validator.validate("visualization", json);
      Vda5050.Visualization telemetry = mapper.readValue(json, Vda5050.Visualization.class);
      Vda5050.Position position = telemetry.mobileRobotPosition();
      var current = store.agv();
      String status = telemetry.velocity() > 0.01 ? "MOVING" : current.status();
      store.updateAgv(position.x(), position.y(), position.theta(), current.battery(), status, current.jobId());
      events.publish("AGV_UPDATED", new ApiModels.AgvView("FL-01", position.x(), position.y(), position.theta(), current.battery(), status, current.jobId()));
    } catch (Exception exception) {
      events.publish("AGV_MESSAGE_REJECTED", java.util.Map.of("topic", topic, "error", exception.getMessage()));
    }
  }

  private void onConnection(String topic, MqttMessage message) {
    try {
      String json = new String(message.getPayload(), StandardCharsets.UTF_8);
      validator.validate("connection", json);
      Vda5050.Connection connection = mapper.readValue(json, Vda5050.Connection.class);
      events.publish("AGV_CONNECTION_UPDATED", connection);
    } catch (Exception exception) {
      events.publish("AGV_MESSAGE_REJECTED", java.util.Map.of("topic", topic, "error", exception.getMessage()));
    }
  }

  private static UUID uuid(String value) {
    try { return value == null || value.isBlank() ? null : UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
  }

  private static boolean hasAction(Vda5050.State state, String type, String status) {
    return state.actionStates().stream().anyMatch(action -> type.equals(action.get("actionType")) && status.equals(action.get("actionStatus")));
  }

  @PreDestroy void close() throws Exception {
    if (client.isConnected()) client.disconnect();
    client.close();
  }
}
