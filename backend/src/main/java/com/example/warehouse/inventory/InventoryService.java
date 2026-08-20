package com.example.warehouse.inventory;

import com.example.warehouse.ApiModels;
import com.example.warehouse.WarehouseStore;
import com.example.warehouse.routing.RoutePlanner;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional inventory writes used by receiving and placement workflows. */
@Service
public class InventoryService {
  private final WarehouseStore store;
  private final RoutePlanner routes;

  public InventoryService(WarehouseStore store, RoutePlanner routes) {
    this.store = store;
    this.routes = routes;
  }

  @Transactional
  public List<ApiModels.LoadView> receive(String sku, int quantity) {
    return store.receive(sku, quantity);
  }

  @Transactional
  public void createPlacement(UUID requestId, List<ApiModels.IncomingLoad> loads, ApiModels.PlacementPlan plan) {
    store.createPlannedJobs(requestId, loads, plan, routes);
  }
}
