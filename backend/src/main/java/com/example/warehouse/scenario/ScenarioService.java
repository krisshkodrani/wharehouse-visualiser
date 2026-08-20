package com.example.warehouse.scenario;

import com.example.warehouse.ApiModels;
import com.example.warehouse.transport.DispatchService;
import com.example.warehouse.mqtt.MqttGateway;
import com.example.warehouse.WarehouseStore;
import com.example.warehouse.events.EventPublisher;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Orchestrates demo scenarios and simulation controls. */
@Service
public class ScenarioService {
  private final WarehouseStore store;
  private final ScenarioSeeder seeder;
  private final MqttGateway mqtt;
  private final DispatchService dispatch;
  private final EventPublisher events;

  public ScenarioService(WarehouseStore store, ScenarioSeeder seeder, MqttGateway mqtt,
      DispatchService dispatch, EventPublisher events) {
    this.store = store;
    this.seeder = seeder;
    this.mqtt = mqtt;
    this.dispatch = dispatch;
    this.events = events;
  }

  public List<ApiModels.ScenarioPreset> presets() {
    return ScenarioPreset.all();
  }

  @Transactional
  public ApiModels.WarehouseSnapshot seed(String presetId) {
    seeder.seed(presetId);
    mqtt.publishControl("RESET", store.runtime());
    dispatch.dispatchNext();
    ApiModels.WarehouseSnapshot snapshot = store.snapshot();
    events.publish("SCENARIO_CHANGED", snapshot);
    return snapshot;
  }

  @Transactional
  public ApiModels.WarehouseSnapshot resetScenario() {
    ApiModels.RuntimeView runtime = seeder.reset();
    mqtt.publishControl("RESET", runtime);
    ApiModels.WarehouseSnapshot snapshot = store.snapshot();
    events.publish("SCENARIO_CHANGED", snapshot);
    return snapshot;
  }

  @Transactional
  public ApiModels.RuntimeView pause() {
    ApiModels.RuntimeView runtime = store.setRuntime("PAUSED");
    mqtt.publishInstantAction("startPause", null);
    events.publish("OPERATIONS_PAUSED", store.snapshot());
    return runtime;
  }

  @Transactional
  public ApiModels.RuntimeView resume() {
    ApiModels.RuntimeView runtime = store.setRuntime("RUNNING");
    mqtt.publishInstantAction("stopPause", null);
    events.publish("OPERATIONS_RESUMED", store.snapshot());
    dispatch.dispatchNext();
    return runtime;
  }

  @Transactional
  public ApiModels.RuntimeView reset() {
    ApiModels.RuntimeView runtime = seeder.reset();
    mqtt.publishControl("RESET", runtime);
    events.publish("SIMULATION_RESET", store.snapshot());
    return runtime;
  }

  @Transactional
  public ApiModels.RuntimeView speed(int multiplier) {
    ApiModels.RuntimeView runtime = store.setTimeScale(multiplier);
    mqtt.publishControl("SET_TIME_SCALE", runtime);
    events.publish("SIMULATION_SPEED_CHANGED", runtime);
    return runtime;
  }
}
