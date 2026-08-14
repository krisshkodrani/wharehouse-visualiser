package com.example.warehouse;

import com.example.warehouse.vda.Vda5050;
import com.example.warehouse.vda.VdaSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
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

  @Transactional
  public void dispatchNext() {
    store.nextQueuedJob().ifPresent(job -> {
      Vda5050.Order order = order(job);
      validator.validate("order", order);
      store.markDispatched(job.id(), write(order));
      events.publish("JOB_UPDATED", view(job, "DISPATCHED"));
    });
  }

  Vda5050.Order order(WarehouseStore.JobRow job) {
    Map<String, WarehouseStore.NodeRow> positions = store.nodes().stream().collect(java.util.stream.Collectors.toMap(WarehouseStore.NodeRow::id, node -> node));
    List<String> repositionRoute = routes.routeFromAgv(job.source());
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
      nodes.add(new Vda5050.Node(id, index * 2L, true, new Vda5050.NodePosition(position.x(), position.z(), "linz", .25), actions));
      if (index > 0) edges.add(new Vda5050.Edge(completeRoute.get(index - 1) + "-" + id, index * 2L - 1, true, List.of(), 1.4));
    }
    return new Vda5050.Order(System.currentTimeMillis() & 0xffffffffL, Vda5050.now(), Vda5050.VERSION,
        Vda5050.MANUFACTURER, Vda5050.SERIAL_NUMBER, job.id().toString(), 0, nodes, edges);
  }

  private static Vda5050.Action action(String type, String loadId, String locationId) {
    return new Vda5050.Action(type, UUID.randomUUID().toString(), "HARD", List.of(
        new Vda5050.ActionParameter("loadId", loadId), new Vda5050.ActionParameter("locationId", locationId)));
  }

  private static ApiModels.JobView view(WarehouseStore.JobRow job, String status) {
    return new ApiModels.JobView(job.id(), job.requestId(), job.sequence(), job.loadId(), job.source(), job.destination(), status, job.route());
  }

  private String write(Object value) {
    try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException(exception); }
  }
}
