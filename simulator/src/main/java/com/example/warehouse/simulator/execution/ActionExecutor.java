package com.example.warehouse.simulator.execution;

import com.example.warehouse.vda.Vda5050;
import java.util.List;

/** Executes node actions sequentially while delegating physical behavior. */
public final class ActionExecutor {
  public interface Context {
    void awaitRunning(long epoch) throws Exception;
    void actionStarted(Vda5050.Action action, Vda5050.Order order, Vda5050.Node node) throws Exception;
    void publishRunning(Vda5050.Action action, Vda5050.Order order, Vda5050.Node node) throws Exception;
    void pick(Vda5050.Action action, Vda5050.Node node, long epoch) throws Exception;
    void drop(Vda5050.Action action, Vda5050.Node node, long epoch) throws Exception;
    void dock(Vda5050.Action action, long epoch) throws Exception;
    void waitForUnsupported(long epoch) throws Exception;
    void actionFinished(Vda5050.Action action, Vda5050.Order order, Vda5050.Node node) throws Exception;
  }

  public void execute(List<Vda5050.Action> actions, Vda5050.Order order,
      Vda5050.Node node, long epoch, Context context) throws Exception {
    for (Vda5050.Action action : actions) {
      context.awaitRunning(epoch);
      context.actionStarted(action, order, node);
      context.publishRunning(action, order, node);
      if ("pick".equals(action.actionType())) context.pick(action, node, epoch);
      else if ("drop".equals(action.actionType())) context.drop(action, node, epoch);
      else if ("dock".equals(action.actionType())) context.dock(action, epoch);
      else context.waitForUnsupported(epoch);
      context.actionFinished(action, order, node);
    }
  }
}
