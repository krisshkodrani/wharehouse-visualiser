package com.example.warehouse.simulator.vehicle;

/** Pure longitudinal motion profile used by the physical simulation loop. */
public final class MotionController {
  private MotionController() {}

  public static double nextSpeed(double current, double remaining, double limit,
      double acceleration, double braking, double seconds) {
    double stoppingDistance = current * current / (2 * braking);
    return stoppingDistance + .03 >= remaining
        ? Math.max(.12, current - braking * seconds)
        : Math.min(limit, current + acceleration * seconds);
  }
}
