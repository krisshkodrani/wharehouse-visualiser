package com.example.warehouse.api;

import com.example.warehouse.ApiModels;
import com.example.warehouse.transport.OperationsService;
import com.example.warehouse.transport.PlanningService;
import com.example.warehouse.WarehouseStore;
import com.example.warehouse.observability.ClientLogService;
import com.example.warehouse.idempotency.IdempotencyService;
import com.example.warehouse.api.ApiDtoMapper;
import com.example.warehouse.api.dto.CreateTransportOrderRequest;
import com.example.warehouse.api.dto.RuntimeResponse;
import com.example.warehouse.api.dto.TransportOrderResponse;
import com.example.warehouse.api.dto.WarehouseSnapshotResponse;

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
public class WarehouseController {
  private final WarehouseStore store;
  private final PlanningService planning;
  private final OperationsService operations;
  private final IdempotencyService idempotency;
  private final ClientLogService clientLogs;

  public WarehouseController(WarehouseStore store, PlanningService planning, OperationsService operations,
      IdempotencyService idempotency, ClientLogService clientLogs) {
    this.store = store; this.planning = planning; this.operations = operations;
    this.idempotency = idempotency; this.clientLogs = clientLogs;
  }

  @GetMapping("/warehouses/linz/snapshot")
  WarehouseSnapshotResponse snapshot() { return ApiDtoMapper.snapshot(store.snapshot()); }

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
  ResponseEntity<WarehouseSnapshotResponse> scenario(@Valid @RequestBody ApiModels.ScenarioRequest request) {
    return ResponseEntity.status(201).body(ApiDtoMapper.snapshot(operations.seedScenario(request.presetId())));
  }

  @PostMapping("/warehouses/linz/scenario/reset")
  WarehouseSnapshotResponse resetScenario() { return ApiDtoMapper.snapshot(operations.resetScenario()); }

  @GetMapping("/warehouses/linz/transport-orders")
  java.util.List<TransportOrderResponse> transportOrders() {
    return store.transportOrders().stream().map(ApiDtoMapper::transportOrder).toList();
  }

  @GetMapping("/warehouses/linz/transport-orders/{id}")
  ResponseEntity<TransportOrderResponse> transportOrder(@PathVariable("id") UUID id) {
    return store.transportOrder(id).map(ApiDtoMapper::transportOrder).map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/warehouses/linz/transport-orders")
  ResponseEntity<TransportOrderResponse> createTransportOrder(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody CreateTransportOrderRequest request) {
    ApiModels.TransportOrderRequest command = new ApiModels.TransportOrderRequest(
        request.type(), request.priority(), request.loadIds(), request.objective());
    ApiModels.TransportOrderView order = idempotency.createTransportOrder(
        idempotencyKey, command, () -> createTransportOrder(command));
    return ResponseEntity.accepted().body(ApiDtoMapper.transportOrder(order));
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
  TransportOrderResponse cancelTransportOrder(@PathVariable("id") UUID id) {
    return ApiDtoMapper.transportOrder(operations.cancel(id));
  }

  /** Accepts diagnostics the browser buffered so the operator's view and the server's
   * land in one stream. Returns 202 with no body: a page reporting a problem must not
   * be made to wait on, or reason about, the reply. */
  @PostMapping("/client-logs")
  ResponseEntity<Void> clientLogs(@Valid @RequestBody ApiModels.ClientLogRequest request) {
    clientLogs.record(request.entries());
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/warehouses/linz/operations/pause")
  RuntimeResponse pause() { return ApiDtoMapper.runtime(operations.pause()); }

  @PostMapping("/warehouses/linz/operations/resume")
  RuntimeResponse resume() { return ApiDtoMapper.runtime(operations.resume()); }

  @PostMapping("/warehouses/linz/operations/reset")
  RuntimeResponse reset() { return ApiDtoMapper.runtime(operations.reset()); }

  @PostMapping("/warehouses/linz/operations/speed")
  RuntimeResponse speed(@Valid @RequestBody ApiModels.SpeedRequest request) {
    return ApiDtoMapper.runtime(operations.speed(request.multiplier()));
  }

  @GetMapping("/putaway-requests/{id}")
  ApiModels.PutawayStatus request(@PathVariable("id") UUID id) { return store.request(id); }

  @GetMapping("/jobs/{id}")
  ResponseEntity<ApiModels.JobView> job(@PathVariable("id") UUID id) {
    return store.job(id).map(job -> ResponseEntity.ok(new ApiModels.JobView(job.id(), job.transportOrderId(), job.sequence(), job.loadId(),
        job.source(), job.destination(), job.status(), job.route()))).orElseGet(() -> ResponseEntity.notFound().build());
  }
}
