package com.example.warehouse;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
class EventPublisher {
  private final SimpMessagingTemplate messaging;
  EventPublisher(SimpMessagingTemplate messaging) { this.messaging = messaging; }
  void publish(String type, Object payload) {
    messaging.convertAndSend("/topic/warehouses/linz", ApiModels.event(type, payload));
  }
}
