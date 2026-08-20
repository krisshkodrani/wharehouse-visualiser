package com.example.warehouse.transport;

import com.example.warehouse.ApiModels;
import com.example.warehouse.WarehouseStore;
import com.example.warehouse.events.EventPublisher;
import com.example.warehouse.inventory.InventoryService;
import com.example.warehouse.mqtt.MqttGateway;
import com.example.warehouse.routing.RoutePlanner;
import com.example.warehouse.scenario.ScenarioService;
import java.util.List;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsService {
  private final WarehouseStore store;
  private final RoutePlanner routes;
  private final DispatchService dispatch;
  private final EventPublisher events;
  private final MqttGateway mqtt;
  private final InventoryService inventory;
  private final ScenarioService scenarios;

  public OperationsService(WarehouseStore store, RoutePlanner routes, DispatchService dispatch, EventPublisher events,
      MqttGateway mqtt, InventoryService inventory, ScenarioService scenarios) {
    this.store = store; this.routes = routes; this.dispatch = dispatch; this.events = events; this.mqtt = mqtt;
    this.inventory = inventory;
    this.scenarios = scenarios;
  }

  @Transactional
  public ApiModels.ReceiveLoadsResponse receive(ApiModels.ReceiveLoadsRequest request) {
    List<ApiModels.LoadView> loads = inventory.receive(request.sku(), request.quantity());
    events.publish("INVENTORY_UPDATED", store.snapshot());
    return new ApiModels.ReceiveLoadsResponse(loads);
  }

  @Transactional
  public ApiModels.PutawayAccepted outbound(ApiModels.OutboundRequest request) {
    UUID id = store.createOutbound(request.loadIds(), routes);
    events.publish("OUTBOUND_PLANNED", store.request(id));
    dispatch.dispatchNext();
    return new ApiModels.PutawayAccepted(id, "VALIDATED");
  }

  @Transactional
  public ApiModels.TransportOrderView outbound(ApiModels.TransportOrderRequest request) {
    UUID id = store.createOutbound(request.loadIds(), request.priority(), request.objective(), store.runtime().scenarioId(), routes);
    events.publish("TRANSPORT_ORDER_UPDATED", store.transportOrder(id).orElseThrow());
    dispatch.dispatchNext();
    return store.transportOrder(id).orElseThrow();
  }

  public java.util.List<ApiModels.ScenarioPreset> scenarioPresets() { return scenarios.presets(); }

  @Transactional
  public ApiModels.WarehouseSnapshot seedScenario(String presetId) {
    return scenarios.seed(presetId);
  }

  @Transactional
  public ApiModels.WarehouseSnapshot resetScenario() {
    return scenarios.resetScenario();
  }

  @Transactional
  public ApiModels.TransportOrderView cancel(UUID orderId) {
    store.activeTaskForOrder(orderId).ifPresent(task -> mqtt.publishInstantAction("cancelOrder", task.id()));
    ApiModels.TransportOrderView order = store.cancelOrder(orderId);
    events.publish("TRANSPORT_ORDER_UPDATED", order);
    return order;
  }

  @Transactional
  public ApiModels.RuntimeView pause() {
    return scenarios.pause();
  }

  @Transactional
  public ApiModels.RuntimeView resume() {
    return scenarios.resume();
  }

  @Transactional
  public ApiModels.RuntimeView reset() {
    return scenarios.reset();
  }

  @Transactional
  public ApiModels.RuntimeView speed(int multiplier) {
    return scenarios.speed(multiplier);
  }

  @Scheduled(fixedDelay = 1000)
  @Transactional
  void finishConveyorTransfers() {
    if (!store.isRunning()) return;
    List<String> shipped = store.completeDueTransfers();
    if (!shipped.isEmpty()) events.publish("SHIPMENTS_COMPLETED", store.snapshot());
  }
}
