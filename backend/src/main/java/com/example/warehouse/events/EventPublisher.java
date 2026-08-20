package com.example.warehouse.events;

import com.example.warehouse.ApiModels;
import com.example.warehouse.WarehouseStore;
import com.example.warehouse.observability.LogContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventPublisher {
  private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
  private final SimpMessagingTemplate messaging;
  private final WarehouseStore store;
  private final java.util.concurrent.atomic.AtomicLong epoch = new java.util.concurrent.atomic.AtomicLong(-1);
  public EventPublisher(SimpMessagingTemplate messaging, WarehouseStore store) { this.messaging = messaging; this.store = store; }
  public void publish(String type, Object payload) {
    long current = epoch.get();
    if (current < 0 || type.contains("RESET") || type.equals("SCENARIO_CHANGED")) {
      current = store.runtime().simulationEpoch();
      epoch.set(current);
    }
    messaging.convertAndSend("/topic/warehouses/linz", ApiModels.event(type, current, payload));
    record(type, null, current);
  }

  public void publish(String type, String eventType, String entityId, String correlationId, Object payload) {
    long current = epoch.get();
    if (current < 0 || type.contains("RESET") || type.equals("SCENARIO_CHANGED")) {
      current = store.runtime().simulationEpoch();
      epoch.set(current);
    }
    messaging.convertAndSend("/topic/warehouses/linz",
        ApiModels.event(type, eventType, entityId, correlationId, current, ApiModels.EVENT_PAYLOAD_VERSION, payload));
    record(type, entityId, current);
  }

  /** Every domain event the UI receives passes through here, which makes this the one
   * place that can log the domain narrative without touching the twenty-odd call
   * sites that raise them. The payload is deliberately not logged: it ranges from a
   * single id to a full order projection, and a log line that sometimes carries a
   * whole snapshot is one nobody can read or query. */
  private void record(String type, String entityId, long epoch) {
    try (var scope = LogContext.of(LogContext.EVENT, type)
        .and(LogContext.EPOCH, epoch)
        .and("entityId", entityId).open()) {
      log.info("domain event");
    }
  }
}
