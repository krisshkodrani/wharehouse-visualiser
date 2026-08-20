package com.example.warehouse.transport;

import com.example.warehouse.ApiModels;
import com.example.warehouse.WarehouseStore;
import com.example.warehouse.events.EventPublisher;
import com.example.warehouse.observability.LogContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** WCS-side deterministic robot cell orchestration. The arm is modelled as a
 * controlled resource; VDA remains responsible only for the AGV movement to
 * and from the handoff position. */
@Service
public class RoboticCellService {
  private static final Logger log = LoggerFactory.getLogger(RoboticCellService.class);
  /** The cell this service orchestrates. Single-cell by design, like the fleet. */
  private static final String CELL_ID = "ROBOT-01";
  private final WarehouseStore store;
  private final EventPublisher events;
  /** Whether the arm is currently held for a vehicle, so entering and leaving the hold is
   * logged once rather than every 250 ms tick. */
  private boolean heldForVehicle;

  public RoboticCellService(WarehouseStore store, EventPublisher events) {
    this.store = store;
    this.events = events;
  }

  @Scheduled(fixedDelay = 250, initialDelay = 1500)
  @Transactional
  public void advance() {
    var candidate = store.nextRobotPick();
    if (candidate.isEmpty()) return;
    Map<String, Object> job = candidate.get();
    String status = String.valueOf(job.get("status"));
    Instant created = (Instant) job.get("createdAt");
    long age = Duration.between(created, Instant.now()).toMillis();
    UUID pickId = (UUID) job.get("id");
    UUID taskId = (UUID) job.get("taskId");
    String cartonId = String.valueOf(job.get("cartonId"));
    // The arm must not move while the forklift is inside the guarding. The AGV has to
    // enter the cell to reach the handoff pad, and nothing used to stop the arm cycling
    // while it was in there -- an articulated arm swinging over a vehicle in the same
    // enclosure, with no light curtain and no interlock. AT_HANDOFF already drives the arm
    // down to the pad, so the hold has to sit ahead of that transition, not just ahead of
    // PICKING. It clears by itself: the vehicle reverses out as soon as the drop completes.
    if (store.guardedCellOccupied(CELL_ID)) {
      if (!heldForVehicle) {
        heldForVehicle = true;
        try (var scope = LogContext.of(LogContext.EVENT, "ROBOT_CELL_HELD")
            .and(LogContext.REASON, "VEHICLE_IN_GUARDED_CELL").open()) {
          log.info("holding the arm: a vehicle is inside {}", CELL_ID);
        }
        events.publish("ROBOT_CELL_HELD", Map.of("robotId", CELL_ID, "reason", "VEHICLE_IN_GUARDED_CELL"));
      }
      return;
    }
    if (heldForVehicle) {
      heldForVehicle = false;
      try (var scope = LogContext.of(LogContext.EVENT, "ROBOT_CELL_RELEASED").open()) {
        log.info("vehicle has left {}: the arm may run", CELL_ID);
      }
      events.publish("ROBOT_CELL_RELEASED", Map.of("robotId", CELL_ID));
    }
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
