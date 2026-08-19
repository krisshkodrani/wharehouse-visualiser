package com.example.warehouse;

import com.example.warehouse.vda.Vda5050;
import com.example.warehouse.vda.VdaSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

@Service
class DispatchService {
  private static final Logger log = LoggerFactory.getLogger(DispatchService.class);
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

  /** Every exit from this method used to be silent, including the two that mean
   * "work exists but cannot start". A queue that stops moving is the single most
   * expensive thing to diagnose in this system -- it previously took hand-written
   * SQL against agv and transport_task to discover a vehicle holding a completed
   * task id -- so each branch now says which one it took and why. */
  @Transactional
  public boolean dispatchNext() {
    var candidates = store.queuedJobs(DISPATCH_CANDIDATES);
    if (candidates.isEmpty()) return false;
    var agvId = store.claimableAgvId();
    if (agvId.isEmpty()) {
      try (var scope = LogContext.of(LogContext.EVENT, "DISPATCH_SKIPPED")
          .and(LogContext.REASON, "NO_CLAIMABLE_VEHICLE").open()) {
        log.info("{} queued task(s) waiting: no vehicle is idle, charged and free of a task", candidates.size());
      }
      return false;
    }
    for (WarehouseStore.TaskRow job : candidates) {
      // Skip past tasks whose destination zone is held by another task rather
      // than abandoning the pass; otherwise a blocked head-of-queue task
      // starves every task behind it.
      if (!store.reserveTaskZone(job.id(), agvId.get(), job.destination())) {
        try (var scope = LogContext.of(LogContext.EVENT, "DISPATCH_SKIPPED")
            .and(LogContext.REASON, "DESTINATION_ZONE_RESERVED")
            .and(LogContext.TASK_ID, job.id())
            .and(LogContext.VEHICLE_ID, agvId.get())
            .and(LogContext.LOAD_ID, job.loadId()).open()) {
          log.info("skipping task: destination {} is reserved by another task", job.destination());
        }
        continue;
      }
      Vda5050.Order order = order(job, agvId.get());
      validator.validate("order", order);
      String payload = write(order);
      store.recordDispatch(job.id(), agvId.get(), order.orderId(), order.orderUpdateId(), payload);
      store.markDispatched(job.id(), agvId.get(), payload);
      events.publish("TRANSPORT_TASK_UPDATED", view(job, "DISPATCHED"));
      try (var scope = LogContext.of(LogContext.EVENT, "TASK_DISPATCHED")
          .and(LogContext.TASK_ID, job.id())
          .and(LogContext.VEHICLE_ID, agvId.get())
          .and(LogContext.LOAD_ID, job.loadId()).open()) {
        log.info("dispatched {} -> {} as VDA order {} update {}",
            job.source(), job.destination(), order.orderId(), order.orderUpdateId());
      }
      return true;
    }
    try (var scope = LogContext.of(LogContext.EVENT, "DISPATCH_SKIPPED")
        .and(LogContext.REASON, "ALL_DESTINATIONS_RESERVED")
        .and(LogContext.VEHICLE_ID, agvId.get()).open()) {
      log.info("vehicle is free but all {} candidate task(s) have a reserved destination", candidates.size());
    }
    return false;
  }

  /** Extends the released base when the vehicle asks for more.
   *
   * <p>A vehicle that drives its released nodes and then stops looks identical, from
   * outside, to one that has broken down: it simply sits at the last released node
   * forever. Both the "nothing to release" and "no dispatch on record" exits are
   * therefore logged, because the difference between them is the difference between
   * a finished base and a lost order. */
  @Transactional
  public void releaseNext(UUID taskId) {
    var dispatch = store.latestDispatch(taskId);
    if (dispatch.isEmpty()) {
      try (var scope = LogContext.of(LogContext.EVENT, "BASE_RELEASE_SKIPPED")
          .and(LogContext.REASON, "NO_DISPATCH_ON_RECORD")
          .and(LogContext.TASK_ID, taskId).open()) {
        log.warn("vehicle asked for more base but no VDA dispatch exists for this task");
      }
      return;
    }
    dispatch.ifPresent(latest -> {
      try {
        Vda5050.Order current = mapper.readValue(latest.payload(), Vda5050.Order.class);
        long released = current.nodes().stream().filter(Vda5050.Node::released).count();
        if (released >= current.nodes().size()) {
          try (var scope = LogContext.of(LogContext.EVENT, "BASE_RELEASE_SKIPPED")
              .and(LogContext.REASON, "ALL_NODES_RELEASED")
              .and(LogContext.TASK_ID, taskId).open()) {
            log.info("every one of the {} node(s) is already released at update {}",
                current.nodes().size(), current.orderUpdateId());
          }
          return;
        }
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
        try (var scope = LogContext.of(LogContext.EVENT, "BASE_RELEASED")
            .and(LogContext.TASK_ID, taskId).open()) {
          log.info("released {} of {} node(s) at update {}", releaseThrough, nodes.size(), update.orderUpdateId());
        }
      } catch (Exception exception) {
        throw new IllegalStateException("Could not extend VDA base", exception);
      }
    });
  }

  /** Grace before a cancelled-but-unreleased pallet is swept up, and before a task that a
   * vehicle never started is reported. Long enough that a healthy round trip always wins. */
  private static final int RECONCILE_GRACE_SECONDS = 15;

  /** Safety net for work that stopped making progress without anyone being told.
   *
   * <p>Two failures used to be silent. A cancelled order whose vehicle never echoed the
   * cancelOrder left its pallet stranded in IN_TRANSIT for ever, because the release is a
   * side effect of that echo. And a task published to the broker but never started simply
   * sat in DISPATCHED: no retry, no timeout, no log -- the pallet just never moved. Neither
   * is acceptable for the command path, which is meant to be the durable half of the system. */
  @Scheduled(fixedDelay = 5000, initialDelay = 8000)
  public void reconcileStalledWork() {
    for (WarehouseStore.TaskRow task : store.tasksAwaitingCancellation(RECONCILE_GRACE_SECONDS)) {
      store.completeCancellation(task.id());
      try (var scope = LogContext.of(LogContext.EVENT, "CANCELLATION_RECONCILED")
          .and(LogContext.REASON, "NO_VEHICLE_ACKNOWLEDGEMENT")
          .and(LogContext.TASK_ID, task.id())
          .and(LogContext.LOAD_ID, task.loadId()).open()) {
        log.warn("released a cancelled task's load after {}s without an acknowledgement from the vehicle",
            RECONCILE_GRACE_SECONDS);
      }
      events.publish("TRANSPORT_TASK_UPDATED", view(task, "CANCELLED"));
    }
    for (WarehouseStore.TaskRow task : store.tasksStalledInDispatch(RECONCILE_GRACE_SECONDS)) {
      try (var scope = LogContext.of(LogContext.EVENT, "DISPATCH_STALLED")
          .and(LogContext.TASK_ID, task.id())
          .and(LogContext.VEHICLE_ID, task.assignedAgvId())
          .and(LogContext.LOAD_ID, task.loadId()).open()) {
        log.warn("task has been DISPATCHED for over {}s without the vehicle starting it", RECONCILE_GRACE_SECONDS);
      }
    }
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
      if (store.enqueueParking(value.parking().id(), order.orderId(), write(order)))
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
