package com.example.warehouse;

import com.example.warehouse.transport.RoboticCellService;
import com.example.warehouse.events.EventPublisher;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The arm must not move while a vehicle is inside the guarding.
 *
 * <p>The AGV has to break the plane of the cell to serve it. OUTBOUND-01's handling pose is
 * x -1.90, which is 1.5 m inside a cell spanning x -7.80..-0.40 and 2.0 m outside the
 * staging area it serves, and the forks reach a further 1.72 m in -- to x -3.62, landing on
 * the handoff pad at x -3.60. That geometry is forced: the pad is the only point the arm can
 * reach while still reaching the conveyor infeed on the far side.
 *
 * <p>So the intrusion is not the defect. The defect was that nothing paired it with an
 * interlock: RoboticCellService referenced the vehicle nowhere at all, and cycled
 * AT_HANDOFF -> PICKING -> PLACING with the forklift standing in the enclosure. Footprint
 * validation never caught it either, because ROBOT-01 ends at -0.40 and OUTBOUND-01 starts
 * at 0.10 -- they do not overlap, so the layout looks clean.
 */
class RoboticCellInterlockTest {

  private static Map<String, Object> queuedPick(UUID pickId) {
    return Map.of("id", pickId, "taskId", UUID.randomUUID(), "cartonId", "CARTON-1",
        "status", "QUEUED", "createdAt", Instant.now().minusSeconds(5));
  }

  @Test void holdsTheArmWhileAVehicleIsInsideTheGuardedCell() {
    WarehouseStore store = mock(WarehouseStore.class);
    EventPublisher events = mock(EventPublisher.class);
    UUID pickId = UUID.randomUUID();
    when(store.nextRobotPick()).thenReturn(Optional.of(queuedPick(pickId)));
    when(store.robotCellAvailable()).thenReturn(true);
    when(store.guardedCellOccupied("ROBOT-01")).thenReturn(true);

    new RoboticCellService(store, events).advance();

    // AT_HANDOFF already swings the arm down to the pad, so the hold has to sit ahead of
    // that transition and not merely ahead of PICKING.
    verify(store, never()).robotPhase(any(), anyString());
    verify(events).publish("ROBOT_CELL_HELD", Map.of("robotId", "ROBOT-01", "reason", "VEHICLE_IN_GUARDED_CELL"));
  }

  @Test void runsOnceTheVehicleHasWithdrawn() {
    WarehouseStore store = mock(WarehouseStore.class);
    EventPublisher events = mock(EventPublisher.class);
    UUID pickId = UUID.randomUUID();
    when(store.nextRobotPick()).thenReturn(Optional.of(queuedPick(pickId)));
    when(store.robotCellAvailable()).thenReturn(true);
    when(store.guardedCellOccupied("ROBOT-01")).thenReturn(false);

    new RoboticCellService(store, events).advance();

    verify(store).robotPhase(pickId, "AT_HANDOFF");
  }

  @Test void logsTheHoldOnceRatherThanOnEveryTick() {
    WarehouseStore store = mock(WarehouseStore.class);
    EventPublisher events = mock(EventPublisher.class);
    when(store.nextRobotPick()).thenReturn(Optional.of(queuedPick(UUID.randomUUID())));
    when(store.robotCellAvailable()).thenReturn(true);
    when(store.guardedCellOccupied("ROBOT-01")).thenReturn(true);

    RoboticCellService service = new RoboticCellService(store, events);
    // advance() runs four times a second; an event per tick would flood the operator view
    // and evict the animation telemetry the e2e specs assert on.
    for (int tick = 0; tick < 8; tick++) service.advance();

    verify(events, times(1)).publish(anyString(), any());
  }

  @Test void releasesTheHoldWhenTheVehicleLeaves() {
    WarehouseStore store = mock(WarehouseStore.class);
    EventPublisher events = mock(EventPublisher.class);
    UUID pickId = UUID.randomUUID();
    when(store.nextRobotPick()).thenReturn(Optional.of(queuedPick(pickId)));
    when(store.robotCellAvailable()).thenReturn(true);
    when(store.guardedCellOccupied("ROBOT-01")).thenReturn(true, true, false);

    RoboticCellService service = new RoboticCellService(store, events);
    service.advance();
    service.advance();
    service.advance();

    verify(events).publish("ROBOT_CELL_RELEASED", Map.of("robotId", "ROBOT-01"));
    verify(store).robotPhase(pickId, "AT_HANDOFF");
  }
}
