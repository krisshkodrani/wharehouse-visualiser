package com.example.warehouse;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PlanningService {
  private final WarehouseStore store;
  private final PlanningWorker worker;

  PlanningService(WarehouseStore store, PlanningWorker worker) { this.store = store; this.worker = worker; }

  ApiModels.PutawayAccepted submit(ApiModels.PutawayRequest request) {
    return submit(request.inboundLoadIds(), "NORMAL", request.operatorPrompt());
  }

  ApiModels.PutawayAccepted submit(List<String> loadIds, String priority, String objective) {
    store.requireRunning();
    UUID id = UUID.randomUUID();
    store.createRequest(id, "PUTAWAY", priority, objective, store.runtime().scenarioId(), loadIds);
    worker.plan(id, new ApiModels.PutawayRequest(loadIds, objective));
    return new ApiModels.PutawayAccepted(id, "PLANNING");
  }
}

@Service
class PlanningWorker {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PlanningWorker.class);
  private final WarehouseStore store;
  private final PlacementAdvisor advisor;
  private final PlanningTransaction transaction;
  private final DispatchService dispatch;
  private final EventPublisher events;

  PlanningWorker(WarehouseStore store, PlacementAdvisor advisor, PlanningTransaction transaction, DispatchService dispatch, EventPublisher events) {
    this.store = store; this.advisor = advisor; this.transaction = transaction; this.dispatch = dispatch; this.events = events;
  }

  @Async
  public void plan(UUID requestId, ApiModels.PutawayRequest request) {
    try {
      List<ApiModels.IncomingLoad> loads = store.incomingLoads(request.inboundLoadIds());
      if (loads.size() != request.inboundLoadIds().size()) throw new IllegalArgumentException("Every requested load must exist in inbound inventory");
      // The operator may name an aisle. Enforce it by narrowing the candidate list
      // rather than by trusting the advisor: with AI_PROVIDER=mock the prompt is
      // never read at all, and even under OpenRouter a constraint we can check is
      // better applied than requested. validate() below then rejects any slot
      // outside this list, so the restriction holds whatever the advisor returns.
      List<ApiModels.CandidateSlot> all = store.candidates();
      List<ApiModels.CandidateSlot> candidates =
          AisleDirective.restrict(all, request.operatorPrompt(), store.aisles());
      // An aisle instruction that silently did nothing was the original defect here,
      // so the narrowing it caused is recorded rather than inferred from the outcome.
      if (candidates.size() != all.size()) {
        try (var scope = LogContext.of(LogContext.EVENT, "AISLE_DIRECTIVE_APPLIED")
            .and(LogContext.ORDER_ID, requestId).open()) {
          log.info("operator named an aisle: {} of {} eligible slot(s) remain", candidates.size(), all.size());
        }
      }
      ApiModels.PlacementPlan plan = advisor.propose(loads, candidates, store.planningMap(), request.operatorPrompt());
      validate(loads, candidates, plan);
      transaction.create(requestId, loads, plan);
      events.publish("PUTAWAY_PLANNED", store.request(requestId));
      try (var scope = LogContext.of(LogContext.EVENT, "PUTAWAY_PLANNED")
          .and(LogContext.ORDER_ID, requestId).open()) {
        log.info("planned {} placement(s) from {} candidate slot(s)", plan.placements().size(), candidates.size());
      }
      dispatch.dispatchNext();
    } catch (Exception exception) {
      String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
      // Planning runs @Async, so a rejection here reaches the operator through an
      // event and nothing else; without this line the reason existed only in a
      // WebSocket frame that had already gone.
      try (var scope = LogContext.of(LogContext.EVENT, "PUTAWAY_REJECTED")
          .and(LogContext.REASON, exception.getClass().getSimpleName())
          .and(LogContext.ORDER_ID, requestId).open()) {
        log.warn("planning refused: {}", message);
      }
      store.rejectRequest(requestId, message);
      events.publish("PUTAWAY_REJECTED", store.request(requestId));
    }
  }

  private static void validate(List<ApiModels.IncomingLoad> loads, List<ApiModels.CandidateSlot> candidates, ApiModels.PlacementPlan plan) {
    if (plan == null || plan.placements() == null || plan.placements().size() != loads.size())
      throw new IllegalArgumentException("Placement plan must contain exactly one placement per load");
    Set<String> expectedLoads = loads.stream().map(ApiModels.IncomingLoad::id).collect(java.util.stream.Collectors.toSet());
    Set<String> allowedSlots = candidates.stream().filter(slot -> slot.freeCapacity() > 0).map(ApiModels.CandidateSlot::id).collect(java.util.stream.Collectors.toSet());
    Set<String> actualLoads = new HashSet<>();
    Set<String> actualSlots = new HashSet<>();
    for (ApiModels.Placement placement : plan.placements()) {
      if (!expectedLoads.contains(placement.loadId()) || !actualLoads.add(placement.loadId())) throw new IllegalArgumentException("Invalid or duplicate load in AI plan");
      if (!allowedSlots.contains(placement.slotId()) || !actualSlots.add(placement.slotId())) throw new IllegalArgumentException("Invalid, unavailable, or duplicate slot in AI plan");
    }
    if (!actualLoads.equals(expectedLoads)) throw new IllegalArgumentException("AI plan omitted a requested load");
  }
}

@Service
class PlanningTransaction {
  private final WarehouseStore store;
  private final RoutePlanner routes;
  PlanningTransaction(WarehouseStore store, RoutePlanner routes) { this.store = store; this.routes = routes; }

  @Transactional
  public void create(UUID requestId, List<ApiModels.IncomingLoad> loads, ApiModels.PlacementPlan plan) {
    store.createPlannedJobs(requestId, loads, plan, routes);
  }
}
