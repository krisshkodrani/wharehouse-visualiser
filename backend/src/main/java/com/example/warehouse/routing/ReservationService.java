package com.example.warehouse.routing;

import com.example.warehouse.WarehouseStore;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Owns destination-zone reservation policy while persistence remains in the repository. */
@Service
public final class ReservationService {
  private final WarehouseStore store;

  public ReservationService(WarehouseStore store) {
    this.store = store;
  }

  public boolean reserveDestination(UUID taskId, String vehicleId, String destination) {
    return store.reserveTaskZone(taskId, vehicleId, destination);
  }
}
