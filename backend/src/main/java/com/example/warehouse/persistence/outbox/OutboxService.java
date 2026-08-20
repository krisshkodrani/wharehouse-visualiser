package com.example.warehouse.persistence.outbox;

import java.util.List;

/** Application-facing lifecycle for durable outbox messages. */
public final class OutboxService {
  private final OutboxRepository repository;

  public OutboxService(OutboxRepository repository) {
    this.repository = repository;
  }

  public List<OutboxMessage> pending() {
    return repository.pending();
  }

  public void published(long id) {
    repository.markSent(id);
  }

  public void failed(long id) {
    repository.markFailed(id);
  }
}
