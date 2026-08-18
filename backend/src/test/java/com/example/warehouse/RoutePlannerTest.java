package com.example.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutePlannerTest {
  @Test void routesFromNearestAgvNodeToPickup() {
    WarehouseStore store = mock(WarehouseStore.class);
    when(store.nearestNodeToAgv()).thenReturn(new WarehouseStore.NodeRow("D", 2, 0));
    when(store.nodeForLocation("PICKUP")).thenReturn("A");
    when(store.nodes()).thenReturn(List.of(
        new WarehouseStore.NodeRow("A", 0, 0), new WarehouseStore.NodeRow("B", 1, 0), new WarehouseStore.NodeRow("D", 2, 0)));
    when(store.edges()).thenReturn(List.of(
        new WarehouseStore.EdgeRow("AB", "A", "B", 1, true), new WarehouseStore.EdgeRow("BD", "B", "D", 1, true)));

    assertEquals(List.of("D", "B", "A"), new RoutePlanner(store).routeFromAgv("PICKUP"));
  }

  @Test void findsLowestCostRoute() {
    WarehouseStore store = mock(WarehouseStore.class);
    when(store.nodeForLocation("SOURCE")).thenReturn("A");
    when(store.nodeForLocation("DESTINATION")).thenReturn("D");
    when(store.nodes()).thenReturn(List.of(
        new WarehouseStore.NodeRow("A", 0, 0), new WarehouseStore.NodeRow("B", 1, 0),
        new WarehouseStore.NodeRow("C", 0, 2), new WarehouseStore.NodeRow("D", 2, 0)));
    when(store.edges()).thenReturn(List.of(
        new WarehouseStore.EdgeRow("AB", "A", "B", 1, true),
        new WarehouseStore.EdgeRow("BD", "B", "D", 1, true),
        new WarehouseStore.EdgeRow("AC", "A", "C", 1, true),
        new WarehouseStore.EdgeRow("CD", "C", "D", 5, true)));

    assertEquals(List.of("A", "B", "D"), new RoutePlanner(store).route("SOURCE", "DESTINATION"));
  }

  @Test void avoidsShortestEdgeWhenRackAndClearanceBlockIt() {
    WarehouseStore store = mock(WarehouseStore.class);
    when(store.nodeForLocation("SOURCE")).thenReturn("A");
    when(store.nodeForLocation("DESTINATION")).thenReturn("B");
    when(store.nodes()).thenReturn(List.of(
        new WarehouseStore.NodeRow("A", 0, 0), new WarehouseStore.NodeRow("B", 4, 0),
        new WarehouseStore.NodeRow("C", 0, 2), new WarehouseStore.NodeRow("D", 4, 2)));
    when(store.edges()).thenReturn(List.of(
        new WarehouseStore.EdgeRow("AB", "A", "B", 1, true),
        new WarehouseStore.EdgeRow("AC", "A", "C", 2, true),
        new WarehouseStore.EdgeRow("CD", "C", "D", 4, true),
        new WarehouseStore.EdgeRow("DB", "D", "B", 2, true)));
    when(store.physicalObstacles()).thenReturn(List.of(
        new WarehouseStore.PhysicalObstacle("RACK", "SHELF", 2, 0, .3, .3, 0, 4)));

    assertEquals(List.of("A", "C", "D", "B"), new RoutePlanner(store).route("SOURCE", "DESTINATION"));
  }

  @Test void rejectsOperationWhenAllRoutesAreBlocked() {
    WarehouseStore store = mock(WarehouseStore.class);
    when(store.nodeForLocation("SOURCE")).thenReturn("A");
    when(store.nodeForLocation("DESTINATION")).thenReturn("B");
    when(store.nodes()).thenReturn(List.of(
        new WarehouseStore.NodeRow("A", 0, 0), new WarehouseStore.NodeRow("B", 4, 0)));
    when(store.edges()).thenReturn(List.of(new WarehouseStore.EdgeRow("AB", "A", "B", 4, true)));
    when(store.physicalObstacles()).thenReturn(List.of(
        new WarehouseStore.PhysicalObstacle("WALL", "WALL", 2, 0, .3, .3, 0, 1.1)));

    assertThrows(IllegalStateException.class, () -> new RoutePlanner(store).route("SOURCE", "DESTINATION"));
  }

  @Test void keepsOutboundHandoffClearOfGuardsAndVehicleOutOfConveyor() {
    WarehouseStore store = mock(WarehouseStore.class);
    when(store.nodeForLocation("SOURCE")).thenReturn("W-C");
    when(store.nodeForLocation("DESTINATION")).thenReturn("OUTBOUND");
    when(store.nodes()).thenReturn(List.of(
        new WarehouseStore.NodeRow("W-C", -18, 10),
        new WarehouseStore.NodeRow("OUTBOUND", -13.1, 13)));
    when(store.edges()).thenReturn(List.of(
        new WarehouseStore.EdgeRow("W-C-OUT", "W-C", "OUTBOUND", 5.745432968, true)));
    when(store.physicalObstacles()).thenReturn(List.of(
        new WarehouseStore.PhysicalObstacle("SHIP-GUARD-S", "BARRIER", -21.15, 12, 2.25, .08, 0, .9),
        new WarehouseStore.PhysicalObstacle("SHIP-GUARD-N", "BARRIER", -19.2, 14, 4.2, .08, 0, .9)));

    assertEquals(List.of("W-C", "OUTBOUND"), new RoutePlanner(store).route("SOURCE", "DESTINATION"));
  }

  /** The C-row travel aisle runs at z=10 and the robot cell is guarded on all four
   * faces. Every storage row must still reach the outbound handoff through the
   * cell's east gate. Uses the real V21 barrier and node coordinates: an earlier
   * layout put the cell's north guard at z=10.1, whose 0.72 m clearance envelope
   * severed the aisle and made outbound routing impossible. */
  @Test void keepsEveryCRowStorageNodeConnectedToOutboundThroughTheRobotCellGate() {
    WarehouseStore store = mock(WarehouseStore.class);
    when(store.nodeForLocation("OUTBOUND-STAGING")).thenReturn("OUTBOUND");
    when(store.nodes()).thenReturn(List.of(
        new WarehouseStore.NodeRow("W-C", -18, 10),
        new WarehouseStore.NodeRow("S-C1", -14, 10),
        new WarehouseStore.NodeRow("S-C2", -8, 10),
        new WarehouseStore.NodeRow("S-C3", -2, 10),
        new WarehouseStore.NodeRow("S-C4", 4, 10),
        new WarehouseStore.NodeRow("OUT-APR-01", 2.6, 14.4),
        new WarehouseStore.NodeRow("OUTBOUND", -1.9, 14.4)));
    when(store.edges()).thenReturn(List.of(
        new WarehouseStore.EdgeRow("C-W-1", "W-C", "S-C1", 4, true),
        new WarehouseStore.EdgeRow("C-1-2", "S-C1", "S-C2", 6, true),
        new WarehouseStore.EdgeRow("C-2-3", "S-C2", "S-C3", 6, true),
        new WarehouseStore.EdgeRow("C-3-4", "S-C3", "S-C4", 6, true),
        new WarehouseStore.EdgeRow("C4-OUT-APR", "S-C4", "OUT-APR-01", 4.63, true),
        new WarehouseStore.EdgeRow("OUT-APR-STG", "OUT-APR-01", "OUTBOUND", 4.5, true)));
    when(store.physicalObstacles()).thenReturn(List.of(
        new WarehouseStore.PhysicalObstacle("ROBOT-CELL-N", "BARRIER", -4.1, 11.50, 3.70, .08, 0, 1.4),
        new WarehouseStore.PhysicalObstacle("ROBOT-CELL-S", "BARRIER", -4.1, 17.30, 3.70, .08, 0, 1.4),
        new WarehouseStore.PhysicalObstacle("ROBOT-CELL-W-N", "BARRIER", -7.8, 12.10, .08, .60, 0, 1.4),
        new WarehouseStore.PhysicalObstacle("ROBOT-CELL-W-S", "BARRIER", -7.8, 16.70, .08, .60, 0, 1.4),
        new WarehouseStore.PhysicalObstacle("ROBOT-CELL-E-N", "BARRIER", -0.4, 12.25, .08, .75, 0, 1.4),
        new WarehouseStore.PhysicalObstacle("ROBOT-CELL-E-S", "BARRIER", -0.4, 16.55, .08, .75, 0, 1.4)));
    when(store.nodeForLocation("C1")).thenReturn("S-C1");
    when(store.nodeForLocation("C2")).thenReturn("S-C2");
    when(store.nodeForLocation("C3")).thenReturn("S-C3");
    when(store.nodeForLocation("C4")).thenReturn("S-C4");

    RoutePlanner planner = new RoutePlanner(store);
    assertEquals(List.of("S-C4", "OUT-APR-01", "OUTBOUND"), planner.route("C4", "OUTBOUND-STAGING"));
    assertEquals(List.of("S-C3", "S-C4", "OUT-APR-01", "OUTBOUND"), planner.route("C3", "OUTBOUND-STAGING"));
    assertEquals(List.of("S-C2", "S-C3", "S-C4", "OUT-APR-01", "OUTBOUND"), planner.route("C2", "OUTBOUND-STAGING"));
    assertEquals(List.of("S-C1", "S-C2", "S-C3", "S-C4", "OUT-APR-01", "OUTBOUND"),
        planner.route("C1", "OUTBOUND-STAGING"));
  }
}
