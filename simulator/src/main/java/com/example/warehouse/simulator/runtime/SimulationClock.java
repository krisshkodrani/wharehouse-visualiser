package com.example.warehouse.simulator.runtime;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Owns simulation epoch, pause state, time scaling, and interruptible waits. */
public final class SimulationClock {
  private final AtomicLong epoch = new AtomicLong(1);
  private volatile boolean paused;
  private volatile int timeScale = 2;

  public long epoch() { return epoch.get(); }
  public boolean paused() { return paused; }
  public int timeScale() { return timeScale; }

  public void pause() { paused = true; }

  public void resume(long nextEpoch) {
    epoch.set(nextEpoch);
    paused = false;
  }

  public void reset(long nextEpoch) {
    epoch.set(nextEpoch);
    paused = false;
  }

  public void setTimeScale(int nextTimeScale) {
    timeScale = Math.max(1, nextTimeScale);
  }

  public void awaitRunning(long expectedEpoch, BooleanSupplier cancelled,
      BooleanSupplier preempted) throws InterruptedException {
    while (paused && expectedEpoch == epoch.get()) Thread.sleep(50);
    if (expectedEpoch != epoch.get()) throw new InterruptedException("Simulation reset");
    if (cancelled.getAsBoolean()) throw new InterruptedException("Order cancelled");
    if (preempted.getAsBoolean()) throw new InterruptedException("Order preempted");
  }

  public void sleep(long milliseconds, long expectedEpoch, BooleanSupplier cancelled,
      BooleanSupplier preempted) throws InterruptedException {
    long remaining = Math.max(1, Math.round((double) milliseconds / timeScale));
    while (remaining > 0) {
      awaitRunning(expectedEpoch, cancelled, preempted);
      long slice = Math.min(remaining, 50);
      Thread.sleep(slice);
      remaining -= slice;
    }
  }
}
