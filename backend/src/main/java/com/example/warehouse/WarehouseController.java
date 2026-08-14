package com.example.warehouse;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

  WarehouseController(WarehouseStore store, PlanningService planning, OperationsService operations) {
    this.store = store; this.planning = planning; this.operations = operations;
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

  @PostMapping("/warehouses/linz/operations/pause")
  ApiModels.RuntimeView pause() { return operations.pause(); }

  @PostMapping("/warehouses/linz/operations/resume")
  ApiModels.RuntimeView resume() { return operations.resume(); }

  @PostMapping("/warehouses/linz/operations/reset")
  ApiModels.RuntimeView reset() { return operations.reset(); }

  @GetMapping("/putaway-requests/{id}")
  ApiModels.PutawayStatus request(@PathVariable("id") UUID id) { return store.request(id); }

  @GetMapping("/jobs/{id}")
  ResponseEntity<ApiModels.JobView> job(@PathVariable("id") UUID id) {
    return store.job(id).map(job -> ResponseEntity.ok(new ApiModels.JobView(job.id(), job.requestId(), job.sequence(), job.loadId(),
        job.source(), job.destination(), job.status(), job.route()))).orElseGet(() -> ResponseEntity.notFound().build());
  }
}
