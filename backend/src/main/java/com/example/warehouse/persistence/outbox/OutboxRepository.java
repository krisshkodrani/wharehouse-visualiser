package com.example.warehouse.persistence.outbox;

import com.example.warehouse.WarehouseStore;
import java.util.List;
import org.springframework.stereotype.Repository;

/** Repository adapter for the durable MQTT outbox. */
@Repository
public class OutboxRepository {
  private final WarehouseStore store;

  public OutboxRepository(WarehouseStore store) {
    this.store = store;
  }

  public List<OutboxMessage> pending() {
    return store.pendingOutbox().stream()
        .map(row -> new OutboxMessage(row.id(), row.topic(), row.payload(), row.qos()))
        .toList();
  }

  public void markSent(long id) { store.sent(id); }
  public void markFailed(long id) { store.failed(id); }
}
