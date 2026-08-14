package com.example.warehouse.vda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates the demo's strict VDA 5050 v3 profile before publish and after receipt. */
public final class VdaSchemaValidator {
  private final ObjectMapper mapper;
  private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

  public VdaSchemaValidator(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public void validate(String messageType, Object value) {
    String resource = "/vda5050-3.0.0/" + messageType + ".schema.json";
    try (InputStream stream = VdaSchemaValidator.class.getResourceAsStream(resource)) {
      if (stream == null) throw new IllegalArgumentException("Unknown VDA message type: " + messageType);
      JsonSchema schema = factory.getSchema(stream);
      JsonNode node = value instanceof String text ? mapper.readTree(text) : mapper.valueToTree(value);
      Set<com.networknt.schema.ValidationMessage> errors = schema.validate(node);
      if (!errors.isEmpty()) {
        throw new IllegalArgumentException("Invalid VDA 5050 " + messageType + ": " +
            errors.stream().map(Object::toString).sorted().collect(Collectors.joining("; ")));
      }
    } catch (IllegalArgumentException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to validate VDA message", exception);
    }
  }
}
