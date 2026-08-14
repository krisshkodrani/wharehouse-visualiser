package com.example.warehouse;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JobExecutionService {
  private final WarehouseStore store;
  private final DispatchService dispatch;
  private final EventPublisher events;

  JobExecutionService(WarehouseStore store, DispatchService dispatch, EventPublisher events) {
    this.store = store; this.dispatch = dispatch; this.events = events;
  }

  @Transactional
  public void executing(UUID jobId) {
    store.job(jobId).ifPresent(job -> {
      store.markExecuting(jobId);
      events.publish("JOB_UPDATED", new ApiModels.JobView(job.id(), job.requestId(), job.sequence(), job.loadId(), job.source(), job.destination(), "EXECUTING", job.route()));
    });
  }

  @Transactional
  public void picked(UUID jobId) {
    store.job(jobId).ifPresent(job -> {
      store.markPicked(jobId);
      store.markExecuting(jobId);
      events.publish("LOAD_PICKED", store.snapshot());
    });
  }

  @Transactional
  public void completeIfArrived(UUID jobId, String lastNodeId) {
    store.job(jobId).ifPresent(job -> {
      if (!job.route().isEmpty() && job.route().getLast().equals(lastNodeId) && !"COMPLETED".equals(job.status())) {
        store.complete(job);
        events.publish("JOB_UPDATED", new ApiModels.JobView(job.id(), job.requestId(), job.sequence(), job.loadId(), job.source(), job.destination(), "COMPLETED", job.route()));
        events.publish("INVENTORY_UPDATED", store.snapshot());
      }
    });
    dispatch.dispatchNext();
  }
}
