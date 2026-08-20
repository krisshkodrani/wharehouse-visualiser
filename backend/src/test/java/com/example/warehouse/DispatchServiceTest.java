package com.example.warehouse;

import com.example.warehouse.transport.DispatchService;
import com.example.warehouse.routing.RoutePlanner;
import com.example.warehouse.events.EventPublisher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.warehouse.vda5050.VdaOrderFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

class DispatchServiceTest {
  @Test void createsValidStationaryDockOrderWhenAgvIsAlreadyAtBay() {
    WarehouseStore store = mock(WarehouseStore.class);
    when(store.nodes()).thenReturn(List.of(new WarehouseStore.NodeRow("PARK01-NODE", -9, -6)));
    RoutePlanner routes = mock(RoutePlanner.class);
    VdaOrderFactory factory = new VdaOrderFactory(store, routes);

    var order = factory.createParkingOrder(List.of("PARK01-NODE"),
        new WarehouseStore.ParkingRow("PARK01", "PARK01-NODE", -9, -6, 0), "FL-01");

    assertEquals(1, order.nodes().size());
    assertEquals(0, order.edges().size());
    assertEquals("dock", order.nodes().getFirst().actions().getFirst().actionType());
  }
}
