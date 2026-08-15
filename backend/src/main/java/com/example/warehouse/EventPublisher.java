package com.example.warehouse;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
class EventPublisher {
  private final SimpMessagingTemplate messaging;
  private final WarehouseStore store;
  private final java.util.concurrent.atomic.AtomicLong epoch = new java.util.concurrent.atomic.AtomicLong(-1);
  EventPublisher(SimpMessagingTemplate messaging, WarehouseStore store) { this.messaging = messaging; this.store = store; }
  void publish(String type, Object payload) {
    long current = epoch.get();
    if (current < 0 || type.contains("RESET") || type.equals("SCENARIO_CHANGED")) {
      current = store.runtime().simulationEpoch();
      epoch.set(current);
    }
    messaging.convertAndSend("/topic/warehouses/linz", ApiModels.event(type, current, payload));
  }
}
