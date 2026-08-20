package com.example.warehouse.simulator.execution;

import com.example.warehouse.vda.Vda5050;

/** Executes one released route leg from the vehicle's live pose to a destination node. */
public final class NodeExecutor {
  public interface Context {
    double x();
    double z();
    void legStarted(Vda5050.Node from, Vda5050.Node to, double distance);
    void driveTo(double x, double z, double speedLimit, long epoch) throws Exception;
    void legArrived(Vda5050.Node node);
  }

  public void execute(Vda5050.Node from, Vda5050.Node to, double speedLimit,
      long epoch, Context context) throws Exception {
    double endX = to.nodePosition().x();
    double endZ = to.nodePosition().y();
    double distance = Math.hypot(endX - context.x(), endZ - context.z());
    context.legStarted(from, to, distance);
    context.driveTo(endX, endZ, speedLimit, epoch);
    context.legArrived(to);
  }
}
