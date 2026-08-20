package com.example.warehouse.inventory;

import com.example.warehouse.ApiModels;
import com.example.warehouse.transport.DispatchService;
import com.example.warehouse.WarehouseStore;
import com.example.warehouse.events.EventPublisher;
import com.example.warehouse.observability.LogContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Asynchronous advisory planning with deterministic constraint enforcement. */
@Service
public class PlacementService {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PlacementService.class);
  private final WarehouseStore store;
  private final PlacementAdvisor advisor;
  private final InventoryService inventory;
  private final DispatchService dispatch;
  private final EventPublisher events;

  public PlacementService(WarehouseStore store, PlacementAdvisor advisor, InventoryService inventory,
      DispatchService dispatch, EventPublisher events) {
    this.store = store;
    this.advisor = advisor;
    this.inventory = inventory;
    this.dispatch = dispatch;
    this.events = events;
  }

  @Async
  public void plan(UUID requestId, ApiModels.PutawayRequest request) {
    try {
      List<ApiModels.IncomingLoad> loads = store.incomingLoads(request.inboundLoadIds());
      if (loads.size() != request.inboundLoadIds().size())
        throw new IllegalArgumentException("Every requested load must exist in inbound inventory");
      List<ApiModels.CandidateSlot> all = store.candidates();
      List<ApiModels.CandidateSlot> candidates =
          AisleDirective.restrict(all, request.operatorPrompt(), store.aisles());
      if (candidates.size() != all.size()) {
        try (var scope = LogContext.of(LogContext.EVENT, "AISLE_DIRECTIVE_APPLIED")
            .and(LogContext.ORDER_ID, requestId).open()) {
          log.info("operator named an aisle: {} of {} eligible slot(s) remain", candidates.size(), all.size());
        }
      }
      ApiModels.PlacementPlan plan =
          advisor.propose(loads, candidates, store.planningMap(), request.operatorPrompt());
      validate(loads, candidates, plan);
      inventory.createPlacement(requestId, loads, plan);
      events.publish("PUTAWAY_PLANNED", store.request(requestId));
      try (var scope = LogContext.of(LogContext.EVENT, "PUTAWAY_PLANNED")
          .and(LogContext.ORDER_ID, requestId).open()) {
        log.info("planned {} placement(s) from {} candidate slot(s)", plan.placements().size(), candidates.size());
      }
      dispatch.dispatchNext();
    } catch (Exception exception) {
      String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
      try (var scope = LogContext.of(LogContext.EVENT, "PUTAWAY_REJECTED")
          .and(LogContext.REASON, exception.getClass().getSimpleName())
          .and(LogContext.ORDER_ID, requestId).open()) {
        log.warn("planning refused: {}", message);
      }
      store.rejectRequest(requestId, message);
      events.publish("PUTAWAY_REJECTED", store.request(requestId));
    }
  }

  private static void validate(List<ApiModels.IncomingLoad> loads,
      List<ApiModels.CandidateSlot> candidates, ApiModels.PlacementPlan plan) {
    if (plan == null || plan.placements() == null || plan.placements().size() != loads.size())
      throw new IllegalArgumentException("Placement plan must contain exactly one placement per load");
    Set<String> expectedLoads = loads.stream().map(ApiModels.IncomingLoad::id)
        .collect(java.util.stream.Collectors.toSet());
    Set<String> allowedSlots = candidates.stream().filter(slot -> slot.freeCapacity() > 0)
        .map(ApiModels.CandidateSlot::id).collect(java.util.stream.Collectors.toSet());
    Set<String> actualLoads = new HashSet<>();
    Set<String> actualSlots = new HashSet<>();
    for (ApiModels.Placement placement : plan.placements()) {
      if (!expectedLoads.contains(placement.loadId()) || !actualLoads.add(placement.loadId()))
        throw new IllegalArgumentException("Invalid or duplicate load in AI plan");
      if (!allowedSlots.contains(placement.slotId()) || !actualSlots.add(placement.slotId()))
        throw new IllegalArgumentException("Invalid, unavailable, or duplicate slot in AI plan");
    }
    if (!actualLoads.equals(expectedLoads))
      throw new IllegalArgumentException("AI plan omitted a requested load");
  }
}
