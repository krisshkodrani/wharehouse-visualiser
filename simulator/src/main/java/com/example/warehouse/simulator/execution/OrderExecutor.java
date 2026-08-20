package com.example.warehouse.simulator.execution;

import com.example.warehouse.vda.Vda5050;
import java.util.List;
import java.util.Map;

/** Owns released-node progression and one-at-a-time order execution lifecycle. */
public final class OrderExecutor {
  public interface Context {
    void awaitRunning(long epoch) throws Exception;
    void awaitReleased(int nodeIndex, long epoch) throws Exception;
    double x();
    double z();
    double theta();
    void pose(double x, double z, double theta);
    void drive(Vda5050.Node from, Vda5050.Node to, long epoch) throws Exception;
    void executeActions(List<Vda5050.Action> actions, Vda5050.Order order,
        Vda5050.Node node, long epoch) throws Exception;
    Vda5050.Order currentOrder();
    void visit(Vda5050.Node node);
    void markComplete(String orderId);
    void publishFinished(Vda5050.Order order, Vda5050.Node node, boolean driving) throws Exception;
    void stopVisualization();
    void orderCompleted(Vda5050.Order order);
    void orderFailed(Vda5050.Order order, Exception exception);
    void cleanupOrder(Vda5050.Order order);
  }

  public void execute(Vda5050.Order order, long epoch, Context context) {
    try {
      context.awaitRunning(epoch);
      List<Vda5050.Node> nodes = order.nodes();
      Vda5050.Node first = nodes.getFirst();
      context.awaitReleased(0, epoch);
      if (Math.hypot(context.x() - first.nodePosition().x(),
          context.z() - first.nodePosition().y()) > .01) {
        Vda5050.Node current = new Vda5050.Node(
            "CURRENT", 0, true,
            new Vda5050.NodePosition(context.x(), context.z(), "linz",
                new Vda5050.AllowedDeviationXY(.25, .25, 0)),
            List.of());
        context.drive(current, first, epoch);
      }
      context.pose(first.nodePosition().x(), first.nodePosition().y(), context.theta());
      context.visit(first);
      context.executeActions(first.actions(), order, first, epoch);
      boolean firstIsLast = nodes.size() == 1;
      if (firstIsLast) context.markComplete(order.orderId());
      context.publishFinished(order, first, !firstIsLast);

      for (int index = 1; index < nodes.size(); index++) {
        context.awaitReleased(index, epoch);
        Vda5050.Order currentOrder = context.currentOrder();
        Vda5050.Node from = currentOrder.nodes().get(index - 1);
        Vda5050.Node to = currentOrder.nodes().get(index);
        context.drive(from, to, epoch);
        context.executeActions(to.actions(), currentOrder, to, epoch);
        context.visit(to);
        boolean driving = index < nodes.size() - 1;
        if (!driving) context.markComplete(order.orderId());
        context.publishFinished(order, to, driving);
      }
      context.stopVisualization();
      context.orderCompleted(order);
    } catch (Exception exception) {
      context.orderFailed(order, exception);
    } finally {
      context.cleanupOrder(order);
    }
  }
}
