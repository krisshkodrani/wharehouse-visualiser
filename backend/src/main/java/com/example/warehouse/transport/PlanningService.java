package com.example.warehouse.transport;

import com.example.warehouse.ApiModels;
import com.example.warehouse.WarehouseStore;
import com.example.warehouse.inventory.PlacementService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PlanningService {
  private final WarehouseStore store;
  private final PlacementService worker;

  public PlanningService(WarehouseStore store, PlacementService worker) { this.store = store; this.worker = worker; }

  public ApiModels.PutawayAccepted submit(ApiModels.PutawayRequest request) {
    return submit(request.inboundLoadIds(), "NORMAL", request.operatorPrompt());
  }

  public ApiModels.PutawayAccepted submit(List<String> loadIds, String priority, String objective) {
    store.requireRunning();
    UUID id = UUID.randomUUID();
    store.createRequest(id, "PUTAWAY", priority, objective, store.runtime().scenarioId(), loadIds);
    worker.plan(id, new ApiModels.PutawayRequest(loadIds, objective));
    return new ApiModels.PutawayAccepted(id, "PLANNING");
  }
}
