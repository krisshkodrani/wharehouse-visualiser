package com.example.warehouse.simulator;

import com.example.warehouse.simulator.vehicle.MotionController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MotionProfileTest {
  @Test void acceleratesWithoutExceedingLimit() {
    assertEquals(.05, MotionController.nextSpeed(0, 10, 2.5, 1, 1.5, .05), .0001);
    assertEquals(2.5, MotionController.nextSpeed(2.5, 10, 2.5, 1, 1.5, .05), .0001);
  }

  @Test void brakesWhenStoppingDistanceReachesRemainingDistance() {
    double next = MotionController.nextSpeed(2, 1.3, 2.5, 1, 1.5, .05);
    assertTrue(next < 2);
  }
}
