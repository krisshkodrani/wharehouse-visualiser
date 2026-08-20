package com.example.warehouse.simulator.vda5050;

import com.example.warehouse.vda.Vda5050;
import com.example.warehouse.vda.VdaSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.eclipse.paho.client.mqttv3.MqttMessage;

/** Validates and decodes inbound VDA instant-action messages. */
public final class InstantActionHandler {
  private final ObjectMapper mapper;
  private final VdaSchemaValidator validator;

  public InstantActionHandler(ObjectMapper mapper, VdaSchemaValidator validator) {
    this.mapper = mapper;
    this.validator = validator;
  }

  public Vda5050.InstantActions decode(MqttMessage message) throws Exception {
    String json = new String(message.getPayload(), StandardCharsets.UTF_8);
    validator.validate("instantActions", json);
    return mapper.readValue(json, Vda5050.InstantActions.class);
  }
}
