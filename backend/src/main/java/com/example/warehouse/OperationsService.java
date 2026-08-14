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
  ApiModels.RuntimeView pause() {
    ApiModels.RuntimeView runtime = store.setRuntime("PAUSED");
    mqtt.publishControl("PAUSE", runtime);
    events.publish("OPERATIONS_PAUSED", store.snapshot());
    return runtime;
  }

  @Transactional
  ApiModels.RuntimeView resume() {
    ApiModels.RuntimeView runtime = store.setRuntime("RUNNING");
    mqtt.publishControl("RESUME", runtime);
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

  @Scheduled(fixedDelay = 1000)
  @Transactional
  void finishConveyorTransfers() {
    if (!store.isRunning()) return;
    List<String> shipped = store.completeDueTransfers();
    if (!shipped.isEmpty()) events.publish("SHIPMENTS_COMPLETED", store.snapshot());
  }
}
