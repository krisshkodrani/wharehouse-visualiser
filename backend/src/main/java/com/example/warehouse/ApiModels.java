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

  public record RackView(String id, String name, double x, double z, double rotationY, int bays) {}
  public record LocationView(String id, String name, String type, int capacity, int occupied, int reserved, double x, double z,
      String rackId, Integer bayIndex, Integer levelIndex, double rotationY, Double operatingWidth, Double operatingDepth) {}
  public record ObstacleView(String id, String type, double x, double z, double width, double depth, double rotationY, double height) {}
  public record LoadView(String id, String item, String status, String locationId, Instant receivedAt, Instant shippedAt) {}
  public record AgvView(String id, double x, double z, double theta, double battery, String status, UUID jobId) {}
  public record JobView(UUID id, UUID requestId, int sequence, String loadId, String source, String destination, String status, List<String> route) {}
  public record RuntimeView(String operationState, long simulationEpoch, Instant changedAt) {}
  public record ConveyorTransferView(UUID id, String loadId, String status, Instant enteredAt, Instant exitDueAt, Instant completedAt) {}
  public record WarehouseSnapshot(String id, String name, double width, double depth, List<RackView> racks,
      List<LocationView> locations, List<LoadView> loads, List<AgvView> agvs, List<JobView> jobs,
      RuntimeView runtime, List<ConveyorTransferView> conveyorTransfers, List<ObstacleView> obstacles) {}

  public record PutawayRequest(@NotEmpty List<String> inboundLoadIds, String operatorPrompt) {}
  public record PutawayAccepted(UUID requestId, String status) {}
  public record PutawayStatus(UUID id, String status, String prompt, String error, Instant createdAt, List<JobView> jobs) {}
  public record ReceiveLoadsRequest(@NotBlank String sku, @Min(1) @Max(50) int quantity) {}
  public record ReceiveLoadsResponse(List<LoadView> loads) {}
  public record OutboundRequest(@NotEmpty List<String> loadIds) {}

  public record CandidateSlot(String id, String name, int freeCapacity, double x, double z) {}
  public record IncomingLoad(String id, String item, String locationId) {}
  public record Placement(String loadId, String slotId, String reason) {}
  public record PlacementPlan(List<Placement> placements) {}
  public record BlockedTile(int column, int row, double centerX, double centerZ, String reason, String referenceId) {}
  public record MapStation(String id, String type, double x, double z, double rotationY, Double width, Double depth) {}
  public record PlanningMap(double tileSize, double originX, double originZ, int columns, int rows,
      boolean omittedTilesArePassable, List<BlockedTile> blockedTiles, List<MapStation> stations,
      List<Map<String, Object>> routeNodes, List<Map<String, Object>> routeEdges) {}

  public record WarehouseEvent(UUID eventId, String type, Instant occurredAt, String warehouseId, Object payload) {}

  public static WarehouseEvent event(String type, Object payload) {
    return new WarehouseEvent(UUID.randomUUID(), type, Instant.now(), "linz", payload);
  }

  @SuppressWarnings("unchecked")
  public static List<String> routeFrom(Object value) {
    return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
  }
}
