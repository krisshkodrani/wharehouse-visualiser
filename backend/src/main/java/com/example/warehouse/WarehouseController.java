package com.example.warehouse;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class WarehouseController {
  private final WarehouseStore store;
  private final PlanningService planning;
  private final OperationsService operations;
  private final IdempotencyService idempotency;

  WarehouseController(WarehouseStore store, PlanningService planning, OperationsService operations, IdempotencyService idempotency) {
    this.store = store; this.planning = planning; this.operations = operations; this.idempotency = idempotency;
  }

  @GetMapping("/warehouses/linz/snapshot")
  ApiModels.WarehouseSnapshot snapshot() { return store.snapshot(); }

  @GetMapping("/warehouses/linz/map")
  ApiModels.PlanningMap map() { return store.planningMap(); }

  @PostMapping("/warehouses/linz/putaway-requests")
  ResponseEntity<ApiModels.PutawayAccepted> putaway(@Valid @RequestBody ApiModels.PutawayRequest request) {
    return ResponseEntity.accepted().body(planning.submit(request));
  }

  @PostMapping("/warehouses/linz/inbound-loads")
  ResponseEntity<ApiModels.ReceiveLoadsResponse> receive(@Valid @RequestBody ApiModels.ReceiveLoadsRequest request) {
    return ResponseEntity.status(201).body(operations.receive(request));
  }

  @PostMapping("/warehouses/linz/outbound-requests")
  ResponseEntity<ApiModels.PutawayAccepted> outbound(@Valid @RequestBody ApiModels.OutboundRequest request) {
    return ResponseEntity.accepted().body(operations.outbound(request));
  }

  @GetMapping("/scenario-presets")
  java.util.List<ApiModels.ScenarioPreset> scenarioPresets() { return operations.scenarioPresets(); }

  @PostMapping("/warehouses/linz/scenario")
  ResponseEntity<ApiModels.WarehouseSnapshot> scenario(@Valid @RequestBody ApiModels.ScenarioRequest request) {
    return ResponseEntity.status(201).body(operations.seedScenario(request.presetId()));
  }

  @PostMapping("/warehouses/linz/scenario/reset")
  ApiModels.WarehouseSnapshot resetScenario() { return operations.resetScenario(); }

  @GetMapping("/warehouses/linz/transport-orders")
  java.util.List<ApiModels.TransportOrderView> transportOrders() { return store.transportOrders(); }

  @GetMapping("/warehouses/linz/transport-orders/{id}")
  ResponseEntity<ApiModels.TransportOrderView> transportOrder(@PathVariable("id") UUID id) {
    return store.transportOrder(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/warehouses/linz/transport-orders")
  ResponseEntity<ApiModels.TransportOrderView> createTransportOrder(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody ApiModels.TransportOrderRequest request) {
    ApiModels.TransportOrderView order = idempotency.createTransportOrder(idempotencyKey, request, () -> createTransportOrder(request));
    return ResponseEntity.accepted().body(order);
  }

  private ApiModels.TransportOrderView createTransportOrder(ApiModels.TransportOrderRequest request) {
    String type = request.type().trim().toUpperCase(java.util.Locale.ROOT);
    String priority = request.priority().trim().toUpperCase(java.util.Locale.ROOT);
    if (!java.util.Set.of("PUTAWAY", "OUTBOUND").contains(type)) throw new IllegalArgumentException("Type must be PUTAWAY or OUTBOUND");
    if (!java.util.Set.of("NORMAL", "HIGH", "URGENT").contains(priority)) throw new IllegalArgumentException("Priority must be NORMAL, HIGH, or URGENT");
    if ("OUTBOUND".equals(type)) return operations.outbound(request);
    ApiModels.PutawayAccepted accepted = planning.submit(request.loadIds(), priority, request.objective());
    return store.transportOrder(accepted.requestId()).orElseThrow();
  }

  @PostMapping("/warehouses/linz/transport-orders/{id}/cancel")
  ApiModels.TransportOrderView cancelTransportOrder(@PathVariable("id") UUID id) { return operations.cancel(id); }

  @PostMapping("/warehouses/linz/operations/pause")
  ApiModels.RuntimeView pause() { return operations.pause(); }

  @PostMapping("/warehouses/linz/operations/resume")
  ApiModels.RuntimeView resume() { return operations.resume(); }

  @PostMapping("/warehouses/linz/operations/reset")
  ApiModels.RuntimeView reset() { return operations.reset(); }

  @PostMapping("/warehouses/linz/operations/speed")
  ApiModels.RuntimeView speed(@Valid @RequestBody ApiModels.SpeedRequest request) { return operations.speed(request.multiplier()); }

  @GetMapping("/putaway-requests/{id}")
  ApiModels.PutawayStatus request(@PathVariable("id") UUID id) { return store.request(id); }

  @GetMapping("/jobs/{id}")
  ResponseEntity<ApiModels.JobView> job(@PathVariable("id") UUID id) {
    return store.job(id).map(job -> ResponseEntity.ok(new ApiModels.JobView(job.id(), job.transportOrderId(), job.sequence(), job.loadId(),
        job.source(), job.destination(), job.status(), job.route()))).orElseGet(() -> ResponseEntity.notFound().build());
  }
}
