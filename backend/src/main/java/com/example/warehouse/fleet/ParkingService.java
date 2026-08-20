package com.example.warehouse.fleet;

import com.example.warehouse.WarehouseStore;
import com.example.warehouse.routing.RoutePlanner;
import com.example.warehouse.vda.VdaSchemaValidator;
import com.example.warehouse.vda5050.VdaOrderFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Selects, validates, reserves, and persists an idle vehicle parking move. */
public final class ParkingService {
  public record ParkingDispatch(String parkingId, List<String> route) {}

  private final WarehouseStore store;
  private final RoutePlanner routes;
  private final VdaOrderFactory orders;
  private final ObjectMapper mapper;
  private final VdaSchemaValidator validator;

  public ParkingService(WarehouseStore store, RoutePlanner routes, VdaOrderFactory orders, ObjectMapper mapper) {
    this.store = store;
    this.routes = routes;
    this.orders = orders;
    this.mapper = mapper;
    this.validator = new VdaSchemaValidator(mapper);
  }

  public Optional<ParkingDispatch> parkIfIdle(String vehicleId) {
    record Candidate(WarehouseStore.ParkingRow parking, List<String> route, double distance) {}
    var positions = store.nodes().stream().collect(Collectors.toMap(WarehouseStore.NodeRow::id, node -> node));
    var candidate = store.parkingTargets().stream().map(parking -> {
      List<String> route = routes.routeFromAgvToNode(parking.nodeId());
      double distance = 0;
      for (int index = 1; index < route.size(); index++) {
        var from = positions.get(route.get(index - 1));
        var to = positions.get(route.get(index));
        distance += Math.hypot(to.x() - from.x(), to.z() - from.z());
      }
      return new Candidate(parking, route, distance);
    }).min(Comparator.comparingDouble(Candidate::distance).thenComparing(value -> value.parking().id()));

    return candidate.flatMap(value -> {
      var order = orders.createParkingOrder(value.route(), value.parking(), vehicleId);
      validator.validate("order", order);
      if (!store.enqueueParking(value.parking().id(), order.orderId(), write(order))) return Optional.empty();
      return Optional.of(new ParkingDispatch(value.parking().id(), List.copyOf(value.route())));
    });
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize parking order", exception);
    }
  }
}
