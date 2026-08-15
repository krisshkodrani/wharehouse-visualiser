package com.example.warehouse.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Starts the two companion forklift vehicles while FL-01 remains the primary demo vehicle. */
@Component
class AgvSimulatorFleet {
  private final List<AgvSimulator> companions;

  AgvSimulatorFleet(ObjectMapper mapper,
      @Value("${warehouse.mqtt-url}") String url,
      @Value("${warehouse.mqtt-user}") String user,
      @Value("${warehouse.mqtt-password}") String password,
      @Value("${warehouse.speed:2.5}") double emptySpeed,
      @Value("${warehouse.loaded-speed:1.8}") double loadedSpeed,
      @Value("${warehouse.docking-speed:0.7}") double dockingSpeed,
      @Value("${warehouse.acceleration:1.0}") double acceleration,
      @Value("${warehouse.braking:1.5}") double braking,
      @Value("${warehouse.battery-consumption-per-metre:0.015}") double batteryConsumptionPerMetre) throws Exception {
    companions = List.of(
        new AgvSimulator(mapper, "FL-02", url, user, password, emptySpeed, loadedSpeed, dockingSpeed, acceleration, braking, batteryConsumptionPerMetre),
        new AgvSimulator(mapper, "FL-03", url, user, password, emptySpeed, loadedSpeed, dockingSpeed, acceleration, braking, batteryConsumptionPerMetre));
  }

  @PostConstruct
  void start() throws Exception {
    for (AgvSimulator companion : companions) companion.start();
  }

  @PreDestroy
  void stop() throws Exception {
    for (AgvSimulator companion : companions) companion.stop();
  }
}
