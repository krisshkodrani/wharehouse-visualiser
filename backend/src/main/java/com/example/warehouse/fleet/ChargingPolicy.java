package com.example.warehouse.fleet;

/** Battery thresholds governing whether a vehicle may accept transport work. */
public final class ChargingPolicy {
  public static final double MINIMUM_DISPATCH_CHARGE = 25.0;

  public boolean mayAcceptTransport(double stateOfCharge) {
    return stateOfCharge >= MINIMUM_DISPATCH_CHARGE;
  }
}
