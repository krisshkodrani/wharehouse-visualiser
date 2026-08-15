package com.example.warehouse.simulator;

final class BatteryModel {
  private BatteryModel() {}

  static double consume(double stateOfCharge, double distanceMetres, double percentagePointsPerMetre) {
    if (!Double.isFinite(percentagePointsPerMetre) || percentagePointsPerMetre < 0)
      throw new IllegalArgumentException("Battery consumption must be a finite, non-negative value");
    return Math.max(0, stateOfCharge - Math.max(0, distanceMetres) * percentagePointsPerMetre);
  }
}
