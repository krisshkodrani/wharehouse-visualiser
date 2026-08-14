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
}
