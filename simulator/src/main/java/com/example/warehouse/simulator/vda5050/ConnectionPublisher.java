package com.example.warehouse.simulator.vda5050;

import com.example.warehouse.simulator.mqtt.SimulatorMqttClient;
import com.example.warehouse.vda.Vda5050;
import com.example.warehouse.vda.VdaSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicLong;

/** Builds and publishes retained VDA connection lifecycle messages. */
public final class ConnectionPublisher {
  private final String serialNumber;
  private final ObjectMapper mapper;
  private final VdaSchemaValidator validator;
  private final SimulatorMqttClient mqtt;
  private final AtomicLong header;

  public ConnectionPublisher(String serialNumber, ObjectMapper mapper,
      VdaSchemaValidator validator, SimulatorMqttClient mqtt, AtomicLong header) {
    this.serialNumber = serialNumber;
    this.mapper = mapper;
    this.validator = validator;
    this.mqtt = mqtt;
    this.header = header;
  }

  public static Vda5050.Connection message(
      String serialNumber, AtomicLong header, String connectionState) {
    return new Vda5050.Connection(
        header.incrementAndGet(), Vda5050.now(), Vda5050.VERSION,
        Vda5050.MANUFACTURER, serialNumber, connectionState);
  }

  public void publish(String connectionState) throws Exception {
    Vda5050.Connection connection = message(serialNumber, header, connectionState);
    validator.validate("connection", connection);
    mqtt.publish(Vda5050.topicPrefix(serialNumber) + "/connection",
        mapper.writeValueAsBytes(connection), 1, true);
  }
}
