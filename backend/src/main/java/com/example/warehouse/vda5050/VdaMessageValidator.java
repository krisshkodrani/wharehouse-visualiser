package com.example.warehouse.vda5050;

import com.example.warehouse.vda.VdaSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Validates standard messages against the pinned VDA schemas. */
public final class VdaMessageValidator {
  private final VdaSchemaValidator schemas;

  public VdaMessageValidator(ObjectMapper mapper) {
    schemas = new VdaSchemaValidator(mapper);
  }

  public void validate(String topic, String json) {
    schemas.validate(topic, json);
  }

  public void validate(String topic, Object payload) {
    schemas.validate(topic, payload);
  }
}
