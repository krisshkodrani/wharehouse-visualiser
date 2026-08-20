package com.example.warehouse.simulator.vda5050;

import com.example.warehouse.vda.Vda5050;
import com.example.warehouse.vda.VdaSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.eclipse.paho.client.mqttv3.MqttMessage;

/** Validates and decodes inbound VDA order messages. */
public final class OrderHandler {
  private final ObjectMapper mapper;
  private final VdaSchemaValidator validator;

  public OrderHandler(ObjectMapper mapper, VdaSchemaValidator validator) {
    this.mapper = mapper;
    this.validator = validator;
  }

  public Vda5050.Order decode(MqttMessage message) throws Exception {
    String json = new String(message.getPayload(), StandardCharsets.UTF_8);
    validator.validate("order", json);
    return mapper.readValue(json, Vda5050.Order.class);
  }
}
