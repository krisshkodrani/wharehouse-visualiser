package com.example.warehouse.scenario;

import com.example.warehouse.ApiModels;
import com.example.warehouse.WarehouseStore;
import com.example.warehouse.routing.RoutePlanner;
import org.springframework.stereotype.Service;

/** Applies scenario fixture data while the store retains SQL ownership. */
@Service
public class ScenarioSeeder {
  private final WarehouseStore store;
  private final RoutePlanner routes;

  public ScenarioSeeder(WarehouseStore store, RoutePlanner routes) {
    this.store = store;
    this.routes = routes;
  }

  public ApiModels.WarehouseSnapshot seed(String presetId) {
    return store.seedScenario(presetId, routes);
  }

  public ApiModels.RuntimeView reset() {
    return store.reset();
  }
}
