package com.example.warehouse.simulator.vehicle;

/** Thread-visible physical and logical state of the simulated fork. */
public final class ForkController {
  private final double liftSpeed;
  private final double extensionSpeed;
  private final double stepSeconds;
  private volatile double height;
  private volatile double extension;
  private volatile String phase = "CHARGING";
  private volatile String carriedLoadId;

  public ForkController(double liftSpeed, double extensionSpeed, int intervalMillis) {
    this.liftSpeed = liftSpeed;
    this.extensionSpeed = extensionSpeed;
    this.stepSeconds = intervalMillis / 1000d;
  }

  public double height() { return height; }
  public double extension() { return extension; }
  public String phase() { return phase; }
  public String carriedLoadId() { return carriedLoadId; }

  public void phase(String value) { phase = value; }
  public void carriedLoad(String loadId) { carriedLoadId = loadId; }

  public void position(double nextHeight, double nextExtension) {
    height = nextHeight;
    extension = nextExtension;
  }

  public void reset(String nextPhase) {
    position(0, 0);
    carriedLoadId = null;
    phase = nextPhase;
  }

  public int stepsTo(double targetHeight, double targetExtension) {
    double duration = Math.max(
        Math.abs(targetHeight - height) / liftSpeed,
        Math.abs(targetExtension - extension) / extensionSpeed);
    return Math.max(1, (int) Math.ceil(duration / stepSeconds));
  }
}
