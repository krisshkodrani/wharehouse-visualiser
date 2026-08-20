package com.example.warehouse.simulator;

import com.example.warehouse.simulator.vehicle.BatteryModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BatteryModelTest {
  @Test void consumesConfiguredPercentagePointsPerMetre() {
    assertEquals(80.5, BatteryModel.consume(82, 100, .015), .0001);
  }

  @Test void neverProducesNegativeCharge() {
    assertEquals(0, BatteryModel.consume(1, 100, .015), .0001);
  }

  @Test void rejectsInvalidConsumptionRates() {
    assertThrows(IllegalArgumentException.class, () -> BatteryModel.consume(82, 10, -.01));
  }
}
