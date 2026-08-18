package com.example.warehouse;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApiModels {
  private ApiModels() {}

  public record RackView(String id, String name, double x, double z, double rotationY, int bays, String canonicalId) {}
  public record LocationView(String id, String name, String type, int capacity, int occupied, int reserved, double x, double z,
      String rackId, Integer bayIndex, Integer levelIndex, double rotationY, Double operatingWidth, Double operatingDepth,
      Double handlingX, Double handlingZ, Double handlingTheta, Double handlingHeight, String canonicalId) {}
  public record ObstacleView(String id, String type, double x, double z, double width, double depth, double rotationY, double height) {}
  public record LoadView(String id, String item, String status, String locationId, String canonicalLocationId, Instant receivedAt, Instant shippedAt) {}
  public record CartonView(String id, String palletId, String sku, int quantity, String status, String locationId,
      Instant pickedAt, Instant shippedAt) {}
  public record AgvView(String id, double x, double z, double theta, double velocity, double battery, String status, UUID taskId,
      boolean charging, String currentStationId, String handlingPhase, double forkHeight, double forkExtension, String carriedLoadId) {}
  public record JobView(UUID id, UUID requestId, int sequence, String loadId, String source, String destination, String status, List<String> route) {}
  public record TransportTaskView(UUID id, UUID transportOrderId, int sequence, String loadId, String source, String destination,
      String status, List<String> route, String assignedAgvId, Instant acceptedAt, Instant startedAt, Instant completedAt, String error) {}
  public record VdaDispatchView(UUID id, UUID taskId, String manufacturer, String serialNumber, String orderId, long orderUpdateId,
      String status, boolean valid, String validationError, String rejectionError, Instant createdAt, Instant publishedAt,
      Instant acceptedAt, Instant finishedAt, String payload) {}
  public record ExecutionEventView(UUID id, UUID transportOrderId, UUID transportTaskId, String vehicleId,
      String eventType, String correlationId, String vdaOrderId, long orderUpdateId, Instant occurredAt, String description) {}
  public record TransportOrderView(UUID id, String type, String priority, String status, String objective, String scenarioId,
      String error, Instant createdAt, Instant completedAt, List<TransportTaskView> tasks,
      List<VdaDispatchView> vdaDispatches, List<ExecutionEventView> executionEvents) {}
  public record ScenarioPreset(String id, String name, String description, int storedLoads, int inboundLoads,
      String orderType, int orderLoads, String priority, int agvBattery) {}
  public record ScenarioView(String id, String name, boolean configured) {}
  public record RuntimeView(String operationState, long simulationEpoch, int timeScale, String scenarioId,
      boolean scenarioConfigured, Instant changedAt) {}
  public record ConveyorTransferView(UUID id, String loadId, String cartonId, String conveyorId, String status,
      Instant enteredAt, Instant exitDueAt, Instant completedAt) {}
  public record RobotCellView(String id, String phase, UUID activePickJobId, Instant updatedAt) {}
  public record WarehouseSnapshot(String id, String name, double width, double depth, List<RackView> racks,
      List<LocationView> locations, List<LoadView> loads, List<AgvView> agvs, List<JobView> jobs,
      List<TransportOrderView> transportOrders, List<TransportTaskView> tasks, ScenarioView scenario,
      RuntimeView runtime, List<ConveyorTransferView> conveyorTransfers, List<ObstacleView> obstacles,
      List<CartonView> cartons, List<RobotCellView> robotCells, List<AisleView> aisles) {}

  public record PutawayRequest(@NotEmpty List<String> inboundLoadIds, String operatorPrompt) {}
  public record PutawayAccepted(UUID requestId, String status) {}
  public record PutawayStatus(UUID id, String status, String prompt, String error, Instant createdAt, List<JobView> jobs) {}
  public record ReceiveLoadsRequest(@NotBlank String sku, @Min(1) @Max(50) int quantity) {}
  public record ReceiveLoadsResponse(List<LoadView> loads) {}
  public record OutboundRequest(@NotEmpty List<String> loadIds) {}
  public record TransportOrderRequest(@NotBlank String type, @NotBlank String priority, @NotEmpty List<String> loadIds, String objective) {}
  public record ScenarioRequest(@NotBlank String presetId) {}
  public record SpeedRequest(@Min(1) @Max(4) int multiplier) {}

  /** A storage slot the planner may choose. {@code aisleId} is what lets an operator
   * instruction like "aisle B" be enforced by filtering rather than hoped for. */
  public record CandidateSlot(String id, String name, int freeCapacity, double x, double z,
      String aisleId, String aisleName) {}
  /** A named travel aisle serving one rack row. Geometry comes from the aisle table
   * so the renderer and the planner agree on where an aisle physically is. */
  public record AisleView(String id, String name, double x, double z, double rotationY,
      double length, double width) {}
  public record IncomingLoad(String id, String item, String locationId) {}
  public record Placement(String loadId, String slotId, String reason) {}
  public record PlacementPlan(List<Placement> placements) {}
  public record BlockedTile(int column, int row, double centerX, double centerZ, String reason, String referenceId) {}
  public record MapStation(String id, String type, double x, double z, double rotationY, Double width, Double depth, String canonicalId) {}
  public record PlanningMap(double tileSize, double originX, double originZ, int columns, int rows,
      boolean omittedTilesArePassable, List<BlockedTile> blockedTiles, List<MapStation> stations,
      List<Map<String, Object>> routeNodes, List<Map<String, Object>> routeEdges, List<AisleView> aisles) {}

  public record WarehouseEvent(UUID eventId, String type, String eventType, Instant occurredAt, String warehouseId, long simulationEpoch,
      String entityId, String correlationId, String payloadVersion, Object payload) {}

  public static final String EVENT_PAYLOAD_VERSION = "1";

  public static WarehouseEvent event(String type, long simulationEpoch, Object payload) {
    return event(type, type, null, null, simulationEpoch, EVENT_PAYLOAD_VERSION, payload);
  }

  public static WarehouseEvent event(String type, String eventType, String entityId, String correlationId, long simulationEpoch, String payloadVersion,
      Object payload) {
    return new WarehouseEvent(UUID.randomUUID(), type, eventType, Instant.now(), "linz", simulationEpoch, entityId, correlationId,
        payloadVersion, payload);
  }

  @SuppressWarnings("unchecked")
  public static List<String> routeFrom(Object value) {
    return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
  }
}
