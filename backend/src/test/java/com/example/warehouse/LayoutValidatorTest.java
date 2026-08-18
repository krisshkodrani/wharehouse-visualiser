package com.example.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutValidatorTest {
  private static WarehouseStore.StationFootprint station(String id, double x, double z, double w, double d) {
    return new WarehouseStore.StationFootprint(id, "STATION", x, z, w, d);
  }

  @Test void acceptsTheOutboundZoneLayout() {
    // The V21 west-flowing line: dock, two conveyor lanes, robot cell, staging apron.
    assertEquals(List.of(), LayoutValidator.overlappingFootprints(List.of(
        station("OUT-DCK-01", -20.6, 13.9, 5.4, 5),
        station("CONV-OUT-01", -12.8, 13.4, 9.6, 1.4),
        station("CONV-OUT-02", -12.8, 15.4, 9.6, 1.4),
        station("ROBOT-01", -4.1, 14.4, 7.4, 5.8),
        station("OUTBOUND-01", 2.6, 14.4, 5, 5),
        station("INBOUND-01", 15.5, -12, 6, 7),
        station("REC-DCK-01", 21, -12, 4, 5),
        station("PARK-01", 11, -6, 2.5, 3.2),
        station("PARK-02", 11, 2, 2.5, 3.2),
        station("PARK-03", 11, 10, 2.5, 3.2),
        station("MAINT-01", 17, 4, 4, 3),
        station("QA-01", 17, 8, 4, 3))));
  }

  @Test void allowsFootprintsThatOnlyTouch() {
    assertEquals(List.of(), LayoutValidator.overlappingFootprints(List.of(
        station("A", 0, 0, 4, 4), station("B", 4, 0, 4, 4))));
  }

  @Test void reportsTheChargingZoneThatUsedToContainAParkingBay() {
    // CHARGE-01 was 8 x 5 centred on PARK-02's exact centre point, so both were
    // drawn as full parking bays with co-planar floor decals.
    List<String> problems = LayoutValidator.overlappingFootprints(List.of(
        station("CHARGE-01", 11, 2, 8, 5), station("PARK-02", 11, 2, 2.5, 3.2)));

    assertEquals(1, problems.size());
    assertTrue(problems.getFirst().contains("CHARGE-01"), problems.getFirst());
    assertTrue(problems.getFirst().contains("PARK-02"), problems.getFirst());
  }

  @Test void reportsTheOutboundStagingAndDockOverlapFromTheOldLayout() {
    List<String> problems = LayoutValidator.overlappingFootprints(List.of(
        station("OUTBOUND-01", -17, 13, 7, 6), station("OUT-DCK-01", -21, 13, 6, 5)));

    assertEquals(1, problems.size());
    assertTrue(problems.getFirst().contains("2.50 m"), problems.getFirst());
  }

  @Test void reportsAnEdgeSeveredByAnObstacleClearanceEnvelope() {
    // A barrier 0.5 m off a travel lane silently removes the edge from the route
    // graph: this is exactly how the original robot-cell north guard at z=10.1
    // disconnected the C row from the outbound handoff.
    WarehouseStore store = mock(WarehouseStore.class);
    when(store.stationFootprints()).thenReturn(List.of());
    when(store.nodes()).thenReturn(List.of(
        new WarehouseStore.NodeRow("S-C2", -8, 10), new WarehouseStore.NodeRow("S-C3", -2, 10)));
    when(store.edges()).thenReturn(List.of(new WarehouseStore.EdgeRow("C-2-3", "S-C2", "S-C3", 6, true)));
    when(store.physicalObstacles()).thenReturn(List.of(
        new WarehouseStore.PhysicalObstacle("ROGUE-GUARD", "BARRIER", -5, 10.5, 3.7, .08, 0, 1.4)));

    List<String> problems = new LayoutValidator(store).validate();

    assertEquals(1, problems.size());
    assertTrue(problems.getFirst().contains("C-2-3"), problems.getFirst());
  }

  @Test void acceptsTheRobotCellPerimeterAgainstTheCRowAisle() {
    WarehouseStore store = mock(WarehouseStore.class);
    when(store.stationFootprints()).thenReturn(List.of());
    when(store.nodes()).thenReturn(List.of(
        new WarehouseStore.NodeRow("S-C2", -8, 10), new WarehouseStore.NodeRow("S-C3", -2, 10),
        new WarehouseStore.NodeRow("S-C4", 4, 10), new WarehouseStore.NodeRow("OUT-APR-01", 2.6, 14.4)));
    when(store.edges()).thenReturn(List.of(
        new WarehouseStore.EdgeRow("C-2-3", "S-C2", "S-C3", 6, true),
        new WarehouseStore.EdgeRow("C-3-4", "S-C3", "S-C4", 6, true),
        new WarehouseStore.EdgeRow("C4-OUT-APR", "S-C4", "OUT-APR-01", 4.63, true)));
    when(store.physicalObstacles()).thenReturn(List.of(
        new WarehouseStore.PhysicalObstacle("ROBOT-CELL-N", "BARRIER", -4.1, 11.50, 3.70, .08, 0, 1.4),
        new WarehouseStore.PhysicalObstacle("ROBOT-CELL-W-N", "BARRIER", -7.8, 12.10, .08, .60, 0, 1.4),
        new WarehouseStore.PhysicalObstacle("ROBOT-CELL-E-N", "BARRIER", -0.4, 12.25, .08, .75, 0, 1.4)));

    assertEquals(List.of(), new LayoutValidator(store).validate());
  }
}
