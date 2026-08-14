package com.example.warehouse.vda;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class VdaSchemaValidatorTest {
  private final VdaSchemaValidator validator = new VdaSchemaValidator(new ObjectMapper());

  @Test void acceptsProfileOrder() {
    var nodes = List.of(
        new Vda5050.Node("INBOUND", 0, true, new Vda5050.NodePosition(8, -5, "linz", .2), List.of()),
        new Vda5050.Node("B1", 2, true, new Vda5050.NodePosition(-7, 2, "linz", .2), List.of()));
    var edges = List.of(new Vda5050.Edge("INBOUND-B1", 1, true, List.of(), 1.4));
    var order = new Vda5050.Order(1, Vda5050.now(), Vda5050.VERSION, "demo", "FL-01", "JOB-1", 0, nodes, edges);
    assertDoesNotThrow(() -> validator.validate("order", order));
  }

  @Test void rejectsWrongVersion() {
    assertThrows(IllegalArgumentException.class, () -> validator.validate("order", "{\"version\":\"2.1.0\"}"));
  }
}
