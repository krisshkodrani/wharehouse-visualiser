package com.example.warehouse.simulator.vda5050;

import com.example.warehouse.simulator.mqtt.SimulatorMqttClient;
import com.example.warehouse.simulator.vehicle.VehicleState;
import com.example.warehouse.vda.Vda5050;
import com.example.warehouse.vda.VdaSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Converts logical execution and vehicle state into validated VDA state messages. */
public final class StatePublisher {
  private final String serialNumber;
  private final ObjectMapper mapper;
  private final VdaSchemaValidator validator;
  private final SimulatorMqttClient mqtt;
  private final AtomicLong header;

  public StatePublisher(String serialNumber, ObjectMapper mapper, VdaSchemaValidator validator,
      SimulatorMqttClient mqtt, AtomicLong header) {
    this.serialNumber = serialNumber;
    this.mapper = mapper;
    this.validator = validator;
    this.mqtt = mqtt;
    this.header = header;
  }

  public long header() {
    return header.get();
  }

  public void publish(Vda5050.Order order, String orderId, String lastNode, long sequence,
      boolean driving, boolean paused, VehicleState vehicle,
      List<Map<String, Object>> actions, List<Map<String, Object>> instantActions) throws Exception {
    List<Map<String, Object>> nodeStates = order == null ? List.of() : order.nodes().stream()
        .filter(node -> node.sequenceId() > sequence)
        .map(node -> Map.<String, Object>of(
            "nodeId", node.nodeId(), "sequenceId", node.sequenceId(), "released", node.released()))
        .toList();
    List<Map<String, Object>> edgeStates = order == null ? List.of() : order.edges().stream()
        .filter(edge -> edge.sequenceId() > sequence)
        .map(edge -> Map.<String, Object>of(
            "edgeId", edge.edgeId(), "sequenceId", edge.sequenceId(), "released", edge.released()))
        .toList();
    boolean newBaseRequest = order != null
        && nodeStates.stream().anyMatch(node -> Boolean.FALSE.equals(node.get("released")))
        && nodeStates.stream().noneMatch(node -> Boolean.TRUE.equals(node.get("released")));
    Vda5050.State state = new Vda5050.State(
        header.incrementAndGet(), Vda5050.now(), Vda5050.VERSION, Vda5050.MANUFACTURER,
        serialNumber, orderId, order == null ? 0 : order.orderUpdateId(), lastNode, sequence,
        driving, paused, newBaseRequest, "AUTOMATIC", position(vehicle),
        new Vda5050.PowerSupply(vehicle.battery(), vehicle.charging()), nodeStates,
        edgeStates, actions, instantActions, List.of(), new Vda5050.SafetyState("NONE", false));
    validator.validate("state", state);
    mqtt.publish(Vda5050.topicPrefix(serialNumber) + "/state",
        mapper.writeValueAsBytes(state), 0, false);
  }

  static Vda5050.Position position(VehicleState vehicle) {
    VehicleState.Pose pose = vehicle.pose();
    return new Vda5050.Position(pose.x(), pose.z(), pose.theta(), "linz", true);
  }
}
