package com.example.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class DispatchServiceTest {
  @Test void createsValidStationaryDockOrderWhenAgvIsAlreadyAtBay() {
    WarehouseStore store = mock(WarehouseStore.class);
    when(store.nodes()).thenReturn(List.of(new WarehouseStore.NodeRow("PARK01-NODE", -9, -6)));
    DispatchService service = new DispatchService(store, new ObjectMapper(), mock(EventPublisher.class), mock(RoutePlanner.class));

    var order = service.movementOrder(List.of("PARK01-NODE"),
        new WarehouseStore.ParkingRow("PARK01", "PARK01-NODE", -9, -6, 0));

    assertEquals(1, order.nodes().size());
    assertEquals(0, order.edges().size());
    assertEquals("dock", order.nodes().getFirst().actions().getFirst().actionType());
  }
}
