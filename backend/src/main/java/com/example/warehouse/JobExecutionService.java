package com.example.warehouse;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JobExecutionService {
  private final WarehouseStore store;
  private final DispatchService dispatch;
  private final EventPublisher events;
  private final WarehouseMetrics metrics;

  JobExecutionService(WarehouseStore store, DispatchService dispatch, EventPublisher events, WarehouseMetrics metrics) {
    this.store = store; this.dispatch = dispatch; this.events = events; this.metrics = metrics;
  }

  @Transactional
  public void executing(UUID jobId) {
    store.job(jobId).ifPresent(job -> {
      store.markExecuting(jobId);
      metrics.taskTransition("EXECUTING");
      events.publish("TRANSPORT_TASK_UPDATED", new ApiModels.JobView(job.id(), job.transportOrderId(), job.sequence(), job.loadId(), job.source(), job.destination(), "EXECUTING", job.route()));
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
        store.finishDispatch(jobId);
        metrics.taskTransition("COMPLETED");
        events.publish("TRANSPORT_TASK_UPDATED", new ApiModels.JobView(job.id(), job.transportOrderId(), job.sequence(), job.loadId(), job.source(), job.destination(), "COMPLETED", job.route()));
        events.publish("INVENTORY_UPDATED", store.snapshot());
      }
    });
    if (!dispatch.dispatchNext()) dispatch.parkIfIdle();
  }

  void agvIdle() { dispatch.dispatchNext(); }

  void releaseNext(UUID taskId) { dispatch.releaseNext(taskId); }

  @Transactional
  void cancelled(UUID taskId) {
    store.completeCancellation(taskId);
    metrics.taskTransition("CANCELLED");
    events.publish("TRANSPORT_TASK_CANCELLED", store.snapshot());
  }
}
