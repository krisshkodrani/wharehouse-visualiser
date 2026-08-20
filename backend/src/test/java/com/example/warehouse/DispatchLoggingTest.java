package com.example.warehouse;

import com.example.warehouse.transport.DispatchService;
import com.example.warehouse.routing.RoutePlanner;
import com.example.warehouse.events.EventPublisher;
import com.example.warehouse.observability.LogContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.warehouse.fleet.VehicleAssignmentPolicy;
import com.example.warehouse.routing.ReservationService;
import com.example.warehouse.vda5050.VdaOrderFactory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.LoggerFactory;

/** A queue that stops moving is the most expensive failure in this system to diagnose.
 *
 * <p>Both times it happened, the reason was a branch in {@link DispatchService} that
 * returned without saying anything, and finding it took hand-written SQL against the
 * agv and transport_task tables. These tests assert that those branches now report
 * which one they took and why, so the coverage cannot quietly rot back.
 *
 * <p>The fields are asserted from MDC rather than the message text because that is
 * what the ECS encoder turns into queryable JSON; a reason that only exists inside a
 * formatted sentence is not something jq can select on.
 */
class DispatchLoggingTest {

  private ListAppender<ILoggingEvent> appender;
  private ch.qos.logback.classic.Logger logger;

  @BeforeEach void attachAppender() {
    logger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(DispatchService.class);
    appender = new ListAppender<>();
    appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
  }

  @AfterEach void detachAppender() {
    logger.detachAppender(appender);
  }

  private DispatchService dispatchService(WarehouseStore store) {
    RoutePlanner routes = mock(RoutePlanner.class);
    return new DispatchService(store, new ObjectMapper(), mock(EventPublisher.class), routes,
        new VehicleAssignmentPolicy(store), new ReservationService(store), new VdaOrderFactory(store, routes));
  }

  private ILoggingEvent single(String event) {
    List<ILoggingEvent> matching = appender.list.stream()
        .filter(candidate -> event.equals(candidate.getMDCPropertyMap().get(LogContext.EVENT)))
        .toList();
    assertThat(matching).describedAs("log records with event=%s", event).hasSize(1);
    return matching.getFirst();
  }

  @Test
  @DisplayName("work waiting with no vehicle to take it is reported, not swallowed")
  void reportsThatNoVehicleCouldBeClaimed() {
    WarehouseStore store = mock(WarehouseStore.class);
    when(store.queuedJobs(anyInt())).thenReturn(List.of(mock(WarehouseStore.TaskRow.class)));
    when(store.claimableAgvId()).thenReturn(Optional.empty());

    assertThat(dispatchService(store).dispatchNext()).isFalse();

    assertThat(single("DISPATCH_SKIPPED").getMDCPropertyMap())
        .containsEntry(LogContext.REASON, "NO_CLAIMABLE_VEHICLE");
  }

  @Test
  @DisplayName("an empty queue stays silent, so a healthy idle demo does not fill the log")
  void staysQuietWhenThereIsSimplyNothingToDo() {
    WarehouseStore store = mock(WarehouseStore.class);
    when(store.queuedJobs(anyInt())).thenReturn(List.of());

    assertThat(dispatchService(store).dispatchNext()).isFalse();

    // dispatchNext runs on a schedule; logging "nothing queued" every pass would bury
    // the cases that matter.
    assertThat(appender.list).isEmpty();
  }

  @Test
  @DisplayName("a vehicle asking for base with no dispatch on record is a warning")
  void warnsWhenBaseIsRequestedForAnUnknownDispatch() {
    WarehouseStore store = mock(WarehouseStore.class);
    UUID taskId = UUID.randomUUID();
    when(store.latestDispatch(taskId)).thenReturn(Optional.empty());

    dispatchService(store).releaseNext(taskId);

    ILoggingEvent record = single("BASE_RELEASE_SKIPPED");
    assertThat(record.getLevel()).isEqualTo(Level.WARN);
    assertThat(record.getMDCPropertyMap())
        .containsEntry(LogContext.REASON, "NO_DISPATCH_ON_RECORD")
        .containsEntry(LogContext.TASK_ID, taskId.toString());
  }

  @Test
  @DisplayName("MDC is restored after a scope so pooled threads do not inherit context")
  void leavesNoContextBehind() {
    org.slf4j.MDC.put(LogContext.TASK_ID, "outer");
    try (var scope = LogContext.of(LogContext.TASK_ID, "inner").and(LogContext.VEHICLE_ID, "FL-01").open()) {
      assertThat(org.slf4j.MDC.get(LogContext.TASK_ID)).isEqualTo("inner");
    }
    assertThat(org.slf4j.MDC.get(LogContext.TASK_ID)).isEqualTo("outer");
    assertThat(org.slf4j.MDC.get(LogContext.VEHICLE_ID)).isNull();
    org.slf4j.MDC.clear();
  }
}
