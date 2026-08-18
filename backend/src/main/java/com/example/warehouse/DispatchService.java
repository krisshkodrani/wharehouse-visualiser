package com.example.warehouse;

import com.example.warehouse.vda.Vda5050;
import com.example.warehouse.vda.VdaSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

@Service
class DispatchService {
  private final WarehouseStore store;
  private final ObjectMapper mapper;
  private final VdaSchemaValidator validator;
  private final EventPublisher events;
  private final RoutePlanner routes;

  DispatchService(WarehouseStore store, ObjectMapper mapper, EventPublisher events, RoutePlanner routes) {
    this.store = store; this.mapper = mapper; this.events = events; this.routes = routes;
    this.validator = new VdaSchemaValidator(mapper);
  }

  /** Number of queued tasks examined per dispatch pass before giving up. Bounded
   * so a long queue of same-destination tasks cannot make one pass expensive. */
  private static final int DISPATCH_CANDIDATES = 12;

  @Transactional
  public boolean dispatchNext() {
    var candidates = store.queuedJobs(DISPATCH_CANDIDATES);
    if (candidates.isEmpty()) return false;
    var agvId = store.claimableAgvId();
    if (agvId.isEmpty()) return false;
    for (WarehouseStore.TaskRow job : candidates) {
      // Skip past tasks whose destination zone is held by another task rather
      // than abandoning the pass; otherwise a blocked head-of-queue task
      // starves every task behind it.
      if (!store.reserveTaskZone(job.id(), agvId.get(), job.destination())) continue;
      Vda5050.Order order = order(job, agvId.get());
      validator.validate("order", order);
      String payload = write(order);
      store.recordDispatch(job.id(), agvId.get(), order.orderId(), order.orderUpdateId(), payload);
      store.markDispatched(job.id(), agvId.get(), payload);
      events.publish("TRANSPORT_TASK_UPDATED", view(job, "DISPATCHED"));
      return true;
    }
    return false;
  }

  @Transactional
  public void releaseNext(UUID taskId) {
    store.latestDispatch(taskId).ifPresent(latest -> {
      try {
        Vda5050.Order current = mapper.readValue(latest.payload(), Vda5050.Order.class);
        long released = current.nodes().stream().filter(Vda5050.Node::released).count();
        if (released >= current.nodes().size()) return;
        int releaseThrough = (int) Math.min(current.nodes().size(), released + 2);
        List<Vda5050.Node> nodes = new ArrayList<>();
        for (int index = 0; index < current.nodes().size(); index++) {
          Vda5050.Node node = current.nodes().get(index);
          nodes.add(new Vda5050.Node(node.nodeId(), node.sequenceId(), index < releaseThrough, node.nodePosition(), node.actions()));
        }
        List<Vda5050.Edge> edges = new ArrayList<>();
        for (int index = 0; index < current.edges().size(); index++) {
          Vda5050.Edge edge = current.edges().get(index);
          edges.add(new Vda5050.Edge(edge.edgeId(), edge.sequenceId(), index < releaseThrough - 1, edge.actions(), edge.maximumSpeed()));
        }
        Vda5050.Order update = new Vda5050.Order(System.currentTimeMillis() & 0xffffffffL, Vda5050.now(), current.version(),
            current.manufacturer(), current.serialNumber(), current.orderId(), current.orderUpdateId() + 1, nodes, edges);
        validator.validate("order", update);
        String payload = write(update);
        store.enqueueOrderUpdate(taskId, update.orderId(), update.orderUpdateId(), payload);
        events.publish("VDA_ORDER_UPDATED", Map.of("taskId", taskId, "orderUpdateId", update.orderUpdateId(),
            "releasedNodes", releaseThrough, "totalNodes", nodes.size()));
      } catch (Exception exception) {
        throw new IllegalStateException("Could not extend VDA base", exception);
      }
    });
  }

  @Scheduled(fixedDelay = 3000, initialDelay = 5000)
  public void parkIfIdle() {
    record Candidate(WarehouseStore.ParkingRow parking, List<String> route, double distance) {}
    Map<String, WarehouseStore.NodeRow> positions = store.nodes().stream().collect(java.util.stream.Collectors.toMap(WarehouseStore.NodeRow::id, node -> node));
    var candidate = store.parkingTargets().stream().map(parking -> {
      List<String> route = routes.routeFromAgvToNode(parking.nodeId());
      double distance = 0;
      for (int index = 1; index < route.size(); index++) {
        var from = positions.get(route.get(index - 1)); var to = positions.get(route.get(index));
        distance += Math.hypot(to.x() - from.x(), to.z() - from.z());
      }
      return new Candidate(parking, route, distance);
    }).min(Comparator.comparingDouble(Candidate::distance).thenComparing(value -> value.parking().id()));
    candidate.ifPresent(value -> {
      Vda5050.Order order = movementOrder(value.route(), value.parking(), "FL-01");
      validator.validate("order", order);
      if (store.enqueueParking(value.parking().id(), write(order)))
        events.publish("AGV_PARKING_DISPATCHED", Map.of("parkingId", value.parking().id(), "route", value.route()));
    });
  }

  Vda5050.Order order(WarehouseStore.TaskRow job, String agvId) {
    Map<String, WarehouseStore.NodeRow> positions = store.nodes().stream().collect(java.util.stream.Collectors.toMap(WarehouseStore.NodeRow::id, node -> node));
    List<String> repositionRoute = routes.routeFromAgv(job.source(), agvId);
    List<String> completeRoute = new ArrayList<>(repositionRoute);
    for (String nodeId : job.route()) {
      if (completeRoute.isEmpty() || !completeRoute.getLast().equals(nodeId)) completeRoute.add(nodeId);
    }
    int pickupIndex = Math.max(0, repositionRoute.size() - 1);
    List<Vda5050.Node> nodes = new ArrayList<>();
    List<Vda5050.Edge> edges = new ArrayList<>();
    for (int index = 0; index < completeRoute.size(); index++) {
      String id = completeRoute.get(index);
      WarehouseStore.NodeRow position = positions.get(id);
      List<Vda5050.Action> actions = new ArrayList<>();
      if (index == pickupIndex) actions.add(action("pick", job.loadId(), job.source()));
      if (index == completeRoute.size() - 1) actions.add(action("drop", job.loadId(), job.destination()));
      boolean released = index < Math.min(3, completeRoute.size());
      nodes.add(new Vda5050.Node(id, index * 2L, released, new Vda5050.NodePosition(position.x(), position.z(), "linz", deviation()), actions));
      if (index > 0) edges.add(new Vda5050.Edge(completeRoute.get(index - 1) + "-" + id, index * 2L - 1, released, List.of(), 2.5));
    }
    return new Vda5050.Order(System.currentTimeMillis() & 0xffffffffL, Vda5050.now(), Vda5050.VERSION,
        Vda5050.MANUFACTURER, agvId, job.id().toString(), 0, nodes, edges);
  }

  Vda5050.Order movementOrder(List<String> route, WarehouseStore.ParkingRow parking, String agvId) {
    Map<String, WarehouseStore.NodeRow> positions = store.nodes().stream().collect(java.util.stream.Collectors.toMap(WarehouseStore.NodeRow::id, node -> node));
    List<Vda5050.Node> nodes = new ArrayList<>();
    List<Vda5050.Edge> edges = new ArrayList<>();
    if (route.size() == 1) {
      String id = route.getFirst();
      WarehouseStore.NodeRow position = positions.get(id);
      nodes.add(new Vda5050.Node(id, 0, true,
          new Vda5050.NodePosition(position.x(), position.z(), "linz", deviation()), List.of(dockAction(parking))));
      return movementOrder(nodes, edges, agvId);
    }
    for (int index = 0; index < route.size(); index++) {
      String id = route.get(index);
      WarehouseStore.NodeRow position = positions.get(id);
      List<Vda5050.Action> actions = index == route.size() - 1 ? List.of(dockAction(parking)) : List.of();
      nodes.add(new Vda5050.Node(id, index * 2L, true, new Vda5050.NodePosition(position.x(), position.z(), "linz", deviation()), actions));
      if (index > 0) edges.add(new Vda5050.Edge(route.get(index - 1) + "-" + id, index * 2L - 1, true, List.of(), .7));
    }
    return movementOrder(nodes, edges, agvId);
  }

  Vda5050.Order movementOrder(List<String> route, WarehouseStore.ParkingRow parking) {
    return movementOrder(route, parking, Vda5050.SERIAL_NUMBER);
  }

  private Vda5050.Action dockAction(WarehouseStore.ParkingRow parking) {
    return new Vda5050.Action("dock", UUID.randomUUID().toString(), "HARD", List.of(
        new Vda5050.ActionParameter("stationId", parking.id()), new Vda5050.ActionParameter("targetTheta", parking.theta())));
  }

  private static Vda5050.AllowedDeviationXY deviation() { return new Vda5050.AllowedDeviationXY(.25, .25, 0); }

  private Vda5050.Order movementOrder(List<Vda5050.Node> nodes, List<Vda5050.Edge> edges, String agvId) {
    return new Vda5050.Order(System.currentTimeMillis() & 0xffffffffL, Vda5050.now(), Vda5050.VERSION,
        Vda5050.MANUFACTURER, agvId, UUID.randomUUID().toString(), 0, nodes, edges);
  }

  private Vda5050.Action action(String type, String loadId, String locationId) {
    WarehouseStore.HandlingRow handling = store.handling(locationId);
    return new Vda5050.Action(type, UUID.randomUUID().toString(), "HARD", List.of(
        new Vda5050.ActionParameter("loadId", loadId), new Vda5050.ActionParameter("locationId", locationId),
        new Vda5050.ActionParameter("targetX", handling.x()), new Vda5050.ActionParameter("targetZ", handling.z()),
        new Vda5050.ActionParameter("targetTheta", handling.theta()), new Vda5050.ActionParameter("targetHeight", handling.height())));
  }

  private static ApiModels.JobView view(WarehouseStore.TaskRow job, String status) {
    return new ApiModels.JobView(job.id(), job.transportOrderId(), job.sequence(), job.loadId(), job.source(), job.destination(), status, job.route());
  }

  private String write(Object value) {
    try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException(exception); }
  }
}
