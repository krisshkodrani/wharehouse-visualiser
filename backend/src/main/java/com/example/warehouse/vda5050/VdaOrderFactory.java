package com.example.warehouse.vda5050;

import com.example.warehouse.WarehouseStore;
import com.example.warehouse.routing.RoutePlanner;
import com.example.warehouse.vda.Vda5050;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Anti-corruption boundary translating business tasks and fleet moves into VDA orders. */
@Component
public final class VdaOrderFactory {
  private final WarehouseStore store;
  private final RoutePlanner routes;

  public VdaOrderFactory(WarehouseStore store, RoutePlanner routes) {
    this.store = store;
    this.routes = routes;
  }

  public Vda5050.Order createTaskOrder(WarehouseStore.TaskRow task, String vehicleId) {
    Map<String, WarehouseStore.NodeRow> positions = positions();
    List<String> repositionRoute = routes.routeFromAgv(task.source(), vehicleId);
    List<String> completeRoute = new ArrayList<>(repositionRoute);
    for (String nodeId : task.route()) {
      if (completeRoute.isEmpty() || !completeRoute.getLast().equals(nodeId)) completeRoute.add(nodeId);
    }
    int pickupIndex = Math.max(0, repositionRoute.size() - 1);
    List<Vda5050.Node> nodes = new ArrayList<>();
    List<Vda5050.Edge> edges = new ArrayList<>();
    for (int index = 0; index < completeRoute.size(); index++) {
      String id = completeRoute.get(index);
      WarehouseStore.NodeRow position = positions.get(id);
      List<Vda5050.Action> actions = new ArrayList<>();
      if (index == pickupIndex) actions.add(loadAction("pick", task.loadId(), task.source()));
      if (index == completeRoute.size() - 1) actions.add(loadAction("drop", task.loadId(), task.destination()));
      boolean released = index < Math.min(3, completeRoute.size());
      nodes.add(node(id, index * 2L, released, position, actions));
      if (index > 0) {
        edges.add(new Vda5050.Edge(completeRoute.get(index - 1) + "-" + id,
            index * 2L - 1, released, List.of(), 2.5));
      }
    }
    return order(nodes, edges, vehicleId, task.id().toString());
  }

  public Vda5050.Order createParkingOrder(List<String> route, WarehouseStore.ParkingRow parking, String vehicleId) {
    Map<String, WarehouseStore.NodeRow> positions = positions();
    List<Vda5050.Node> nodes = new ArrayList<>();
    List<Vda5050.Edge> edges = new ArrayList<>();
    if (route.size() == 1) {
      String id = route.getFirst();
      nodes.add(node(id, 0, true, positions.get(id), List.of(dockAction(parking))));
      return order(nodes, edges, vehicleId, UUID.randomUUID().toString());
    }
    for (int index = 0; index < route.size(); index++) {
      String id = route.get(index);
      List<Vda5050.Action> actions = index == route.size() - 1 ? List.of(dockAction(parking)) : List.of();
      nodes.add(node(id, index * 2L, true, positions.get(id), actions));
      if (index > 0) {
        edges.add(new Vda5050.Edge(route.get(index - 1) + "-" + id,
            index * 2L - 1, true, List.of(), .7));
      }
    }
    return order(nodes, edges, vehicleId, UUID.randomUUID().toString());
  }

  private Map<String, WarehouseStore.NodeRow> positions() {
    return store.nodes().stream().collect(java.util.stream.Collectors.toMap(WarehouseStore.NodeRow::id, node -> node));
  }

  private Vda5050.Node node(String id, long sequenceId, boolean released, WarehouseStore.NodeRow position,
      List<Vda5050.Action> actions) {
    return new Vda5050.Node(id, sequenceId, released,
        new Vda5050.NodePosition(position.x(), position.z(), "linz", new Vda5050.AllowedDeviationXY(.25, .25, 0)), actions);
  }

  private Vda5050.Action dockAction(WarehouseStore.ParkingRow parking) {
    return new Vda5050.Action("dock", UUID.randomUUID().toString(), "HARD", List.of(
        new Vda5050.ActionParameter("stationId", parking.id()),
        new Vda5050.ActionParameter("targetTheta", parking.theta())));
  }

  private Vda5050.Action loadAction(String type, String loadId, String locationId) {
    WarehouseStore.HandlingRow handling = store.handling(locationId);
    return new Vda5050.Action(type, UUID.randomUUID().toString(), "HARD", List.of(
        new Vda5050.ActionParameter("loadId", loadId), new Vda5050.ActionParameter("locationId", locationId),
        new Vda5050.ActionParameter("targetX", handling.x()), new Vda5050.ActionParameter("targetZ", handling.z()),
        new Vda5050.ActionParameter("targetTheta", handling.theta()),
        new Vda5050.ActionParameter("targetHeight", handling.height())));
  }

  private Vda5050.Order order(List<Vda5050.Node> nodes, List<Vda5050.Edge> edges, String vehicleId, String orderId) {
    return new Vda5050.Order(System.currentTimeMillis() & 0xffffffffL, Vda5050.now(), Vda5050.VERSION,
        Vda5050.MANUFACTURER, vehicleId, orderId, 0, nodes, edges);
  }
}
