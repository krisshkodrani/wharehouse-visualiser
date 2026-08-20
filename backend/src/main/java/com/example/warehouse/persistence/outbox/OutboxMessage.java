package com.example.warehouse.persistence.outbox;

public record OutboxMessage(long id, String topic, String payload, int qos) {}
