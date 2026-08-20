package com.example.warehouse.simulator.vda5050;

import com.example.warehouse.simulator.mqtt.SimulatorMqttClient;
import com.example.warehouse.simulator.vehicle.VehicleState;
import com.example.warehouse.vda.Vda5050;
import com.example.warehouse.vda.VdaSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicLong;

/** Converts physical vehicle state into validated latest-value visualization telemetry. */
public final class VisualizationPublisher {
  private final String serialNumber;
  private final ObjectMapper mapper;
  private final VdaSchemaValidator validator;
  private final SimulatorMqttClient mqtt;
  private final AtomicLong header = new AtomicLong();

  public VisualizationPublisher(String serialNumber, ObjectMapper mapper,
      VdaSchemaValidator validator, SimulatorMqttClient mqtt) {
    this.serialNumber = serialNumber;
    this.mapper = mapper;
    this.validator = validator;
    this.mqtt = mqtt;
  }

  public void publish(VehicleState vehicle, long stateHeader) throws Exception {
    Vda5050.Visualization visualization = new Vda5050.Visualization(
        header.incrementAndGet(), Vda5050.now(), Vda5050.VERSION, Vda5050.MANUFACTURER,
        serialNumber, stateHeader, StatePublisher.position(vehicle),
        new Vda5050.Velocity(vehicle.velocity(), 0, 0));
    validator.validate("visualization", visualization);
    mqtt.publish(Vda5050.topicPrefix(serialNumber) + "/visualization",
        mapper.writeValueAsBytes(visualization), 0, false);
  }
}
