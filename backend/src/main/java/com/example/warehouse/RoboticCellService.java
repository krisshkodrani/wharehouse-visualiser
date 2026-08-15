package com.example.warehouse;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** WCS-side deterministic robot cell orchestration. The arm is modelled as a
 * controlled resource; VDA remains responsible only for the AGV movement to
 * and from the handoff position. */
@Service
class RoboticCellService {
  private final WarehouseStore store;
  private final EventPublisher events;

  RoboticCellService(WarehouseStore store, EventPublisher events) {
    this.store = store;
    this.events = events;
  }

  @Scheduled(fixedDelay = 250, initialDelay = 1500)
  @Transactional
  void advance() {
    var candidate = store.nextRobotPick();
    if (candidate.isEmpty()) return;
    Map<String, Object> job = candidate.get();
    String status = String.valueOf(job.get("status"));
    Instant created = (Instant) job.get("createdAt");
    long age = Duration.between(created, Instant.now()).toMillis();
    UUID pickId = (UUID) job.get("id");
    UUID taskId = (UUID) job.get("taskId");
    String cartonId = String.valueOf(job.get("cartonId"));
    if ("QUEUED".equals(status)) {
      if (!store.robotCellAvailable()) return;
      store.robotPhase(pickId, "AT_HANDOFF");
      events.publish("ROBOT_PHASE_CHANGED", Map.of("robotId", "ROBOT-01", "phase", "AT_HANDOFF", "cartonId", cartonId));
      return;
    }
    if (age < 450) return;
    if ("AT_HANDOFF".equals(status)) {
      store.robotPhase(pickId, "PICKING");
      events.publish("ROBOT_PHASE_CHANGED", Map.of("robotId", "ROBOT-01", "phase", "PICKING", "cartonId", cartonId));
    } else if ("PICKING".equals(status)) {
      store.robotPhase(pickId, "PLACING");
      events.publish("ROBOT_PHASE_CHANGED", Map.of("robotId", "ROBOT-01", "phase", "PLACING", "cartonId", cartonId));
    } else if ("PLACING".equals(status)) {
      store.completeRobotPick(pickId, taskId, cartonId);
      events.publish("ROBOT_PHASE_CHANGED", Map.of("robotId", "ROBOT-01", "phase", "IDLE", "cartonId", cartonId));
      events.publish("CONVEYOR_TRANSFER_CREATED", store.snapshot());
    }
  }
}
