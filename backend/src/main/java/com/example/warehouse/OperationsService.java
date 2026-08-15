package com.example.warehouse;

import java.util.List;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OperationsService {
  private final WarehouseStore store;
  private final RoutePlanner routes;
  private final DispatchService dispatch;
  private final EventPublisher events;
  private final MqttGateway mqtt;

  OperationsService(WarehouseStore store, RoutePlanner routes, DispatchService dispatch, EventPublisher events, MqttGateway mqtt) {
    this.store = store; this.routes = routes; this.dispatch = dispatch; this.events = events; this.mqtt = mqtt;
  }

  @Transactional
  ApiModels.ReceiveLoadsResponse receive(ApiModels.ReceiveLoadsRequest request) {
    List<ApiModels.LoadView> loads = store.receive(request.sku(), request.quantity());
    events.publish("INVENTORY_UPDATED", store.snapshot());
    return new ApiModels.ReceiveLoadsResponse(loads);
  }

  @Transactional
  ApiModels.PutawayAccepted outbound(ApiModels.OutboundRequest request) {
    UUID id = store.createOutbound(request.loadIds(), routes);
    events.publish("OUTBOUND_PLANNED", store.request(id));
    dispatch.dispatchNext();
    return new ApiModels.PutawayAccepted(id, "VALIDATED");
  }

  @Transactional
  ApiModels.TransportOrderView outbound(ApiModels.TransportOrderRequest request) {
    UUID id = store.createOutbound(request.loadIds(), request.priority(), request.objective(), store.runtime().scenarioId(), routes);
    events.publish("TRANSPORT_ORDER_UPDATED", store.transportOrder(id).orElseThrow());
    dispatch.dispatchNext();
    return store.transportOrder(id).orElseThrow();
  }

  java.util.List<ApiModels.ScenarioPreset> scenarioPresets() { return store.scenarioPresets(); }

  @Transactional
  ApiModels.WarehouseSnapshot seedScenario(String presetId) {
    store.seedScenario(presetId, routes);
    mqtt.publishControl("RESET", store.runtime());
    dispatch.dispatchNext();
    ApiModels.WarehouseSnapshot snapshot = store.snapshot();
    events.publish("SCENARIO_CHANGED", snapshot);
    return snapshot;
  }

  @Transactional
  ApiModels.WarehouseSnapshot resetScenario() {
    ApiModels.RuntimeView runtime = store.reset();
    mqtt.publishControl("RESET", runtime);
    ApiModels.WarehouseSnapshot snapshot = store.snapshot();
    events.publish("SCENARIO_CHANGED", snapshot);
    return snapshot;
  }

  @Transactional
  ApiModels.TransportOrderView cancel(UUID orderId) {
    store.activeTaskForOrder(orderId).ifPresent(task -> mqtt.publishInstantAction("cancelOrder", task.id()));
    ApiModels.TransportOrderView order = store.cancelOrder(orderId);
    events.publish("TRANSPORT_ORDER_UPDATED", order);
    return order;
  }

  void demoEvent(ApiModels.DemoEventRequest request) {
    String type = request.type().trim().toUpperCase(java.util.Locale.ROOT);
    if (!java.util.Set.of("VDA_REJECTION", "BLOCK_ROUTE").contains(type)) throw new IllegalArgumentException("Unsupported demo event");
    events.publish(type, java.util.Map.of("taskId", request.taskId() == null ? "" : request.taskId().toString(),
        "message", "VDA_REJECTION".equals(type) ? "Order rejected: unsupported demo action parameter" : "Route blocked; replanning requested"));
  }

  @Transactional
  ApiModels.RuntimeView pause() {
    ApiModels.RuntimeView runtime = store.setRuntime("PAUSED");
    mqtt.publishInstantAction("startPause", null);
    events.publish("OPERATIONS_PAUSED", store.snapshot());
    return runtime;
  }

  @Transactional
  ApiModels.RuntimeView resume() {
    ApiModels.RuntimeView runtime = store.setRuntime("RUNNING");
    mqtt.publishInstantAction("stopPause", null);
    events.publish("OPERATIONS_RESUMED", store.snapshot());
    dispatch.dispatchNext();
    return runtime;
  }

  @Transactional
  ApiModels.RuntimeView reset() {
    ApiModels.RuntimeView runtime = store.reset();
    mqtt.publishControl("RESET", runtime);
    events.publish("SIMULATION_RESET", store.snapshot());
    return runtime;
  }

  @Transactional
  ApiModels.RuntimeView speed(int multiplier) {
    ApiModels.RuntimeView runtime = store.setTimeScale(multiplier);
    mqtt.publishControl("SET_TIME_SCALE", runtime);
    events.publish("SIMULATION_SPEED_CHANGED", runtime);
    return runtime;
  }

  @Scheduled(fixedDelay = 1000)
  @Transactional
  void finishConveyorTransfers() {
    if (!store.isRunning()) return;
    List<String> shipped = store.completeDueTransfers();
    if (!shipped.isEmpty()) events.publish("SHIPMENTS_COMPLETED", store.snapshot());
  }
}
