package com.example.warehouse.vda;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** VDA 5050 3.0.0 messages used by the control-tower demo. */
public final class Vda5050 {
  public static final String VERSION = "3.0.0";
  public static final String MANUFACTURER = "demo";
  public static final String SERIAL_NUMBER = "FL-01";
  public static final String TOPIC_PREFIX = "vda5050/v3/" + MANUFACTURER + "/" + SERIAL_NUMBER;

  private Vda5050() {}

  public record AllowedDeviationXY(double a, double b, double theta) {}
  public record NodePosition(double x, double y, String mapId, AllowedDeviationXY allowedDeviationXY) {}
  public record ActionParameter(String key, Object value) {}
  public record Action(String actionType, String actionId, String blockingType, List<ActionParameter> actionParameters) {}
  public record Node(String nodeId, long sequenceId, boolean released, NodePosition nodePosition, List<Action> actions) {}
  public record Edge(String edgeId, long sequenceId, boolean released, List<Action> actions, Double maximumSpeed) {}

  public record Order(
      long headerId,
      String timestamp,
      String version,
      String manufacturer,
      String serialNumber,
      String orderId,
      long orderUpdateId,
      List<Node> nodes,
      List<Edge> edges) {}

  public record Position(double x, double y, double theta, String mapId, boolean localized) {}
  public record Velocity(double vx, double vy, double omega) {}
  public record PowerSupply(double stateOfCharge, boolean charging) {}
  public record SafetyState(String activeEmergencyStop, boolean fieldViolation) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record State(
      long headerId,
      String timestamp,
      String version,
      String manufacturer,
      String serialNumber,
      String orderId,
      long orderUpdateId,
      String lastNodeId,
      long lastNodeSequenceId,
      boolean driving,
      boolean paused,
      boolean newBaseRequest,
      String operatingMode,
      Position mobileRobotPosition,
      PowerSupply powerSupply,
      List<Map<String, Object>> nodeStates,
      List<Map<String, Object>> edgeStates,
      List<Map<String, Object>> actionStates,
      List<Map<String, Object>> instantActionStates,
      List<Map<String, Object>> errors,
      SafetyState safetyState) {}

  public record InstantActions(long headerId, String timestamp, String version, String manufacturer, String serialNumber,
      List<Action> actions) {}

  public record Visualization(
      long headerId,
      String timestamp,
      String version,
      String manufacturer,
      String serialNumber,
      long referenceStateHeaderId,
      Position mobileRobotPosition,
      Velocity velocity) {}

  public record Connection(
      long headerId,
      String timestamp,
      String version,
      String manufacturer,
      String serialNumber,
      String connectionState) {}

  public static String now() {
    return Instant.now().toString();
  }
}
