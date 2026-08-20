package com.example.warehouse.simulator.vehicle;

/** Thread-visible logical and physical state of one simulated vehicle. */
public final class VehicleState {
  public record Pose(double x, double z, double theta) {}

  private volatile Pose pose = new Pose(11, -6, 0);
  private volatile double velocity;
  private volatile double battery = 82;
  private volatile String stationId = "PARK-01";
  private volatile boolean charging = true;

  public Pose pose() { return pose; }
  public double velocity() { return velocity; }
  public double battery() { return battery; }
  public String stationId() { return stationId; }
  public boolean charging() { return charging; }

  public void pose(double x, double z, double theta) { pose = new Pose(x, z, theta); }
  public void velocity(double value) { velocity = value; }
  public void battery(double value) { battery = value; }
  public void stationId(String value) { stationId = value; }
  public void charging(boolean value) { charging = value; }

  public void reset(double x, double z, double theta, double stateOfCharge,
      String currentStationId, boolean isCharging) {
    pose(x, z, theta);
    velocity = 0;
    battery = stateOfCharge;
    stationId = currentStationId;
    charging = isCharging;
  }
}
