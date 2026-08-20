package com.example.warehouse.fleet;

import com.example.warehouse.WarehouseStore;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Selects a vehicle eligible to own the next transport task. */
@Component
public final class VehicleAssignmentPolicy {
  private final WarehouseStore store;
  private final ChargingPolicy charging = new ChargingPolicy();

  public VehicleAssignmentPolicy(WarehouseStore store) {
    this.store = store;
  }

  public Optional<String> selectVehicle() {
    return store.claimableAgvId()
        .filter(ignored -> charging.mayAcceptTransport(store.agv().battery()));
  }
}
