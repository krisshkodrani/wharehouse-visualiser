package com.example.warehouse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.warehouse.vda.Vda5050;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;

class MqttGatewayTest {
  private static final String TOPIC = "vda5050/v3/demo/FL-01/state";
  private static final UUID TASK = UUID.fromString("11111111-2222-3333-4444-555555555555");

  private final ObjectMapper mapper = new ObjectMapper();
  private final WarehouseStore store = mock(WarehouseStore.class);
  private final JobExecutionService execution = mock(JobExecutionService.class);
  private final WarehouseMetrics metrics = mock(WarehouseMetrics.class);

  private MqttGateway gateway() throws Exception {
    when(store.agv("FL-01")).thenReturn(new ApiModels.AgvView("FL-01", 11, -6, 0, 0, 80, "IDLE", null,
        false, "PARK-01", "IDLE", 0, 0, null));
    // The client is constructed but never connected, so no broker is needed.
    return new MqttGateway(mapper, store, execution, mock(EventPublisher.class), metrics,
        "tcp://localhost:1883", "warehouse", "warehouse");
  }

  private MqttMessage state(String cancelActionId) throws Exception {
    List<Map<String, Object>> instantActions = cancelActionId == null ? List.of()
        : List.of(Map.of("actionId", cancelActionId, "actionType", "cancelOrder", "actionStatus", "FINISHED"));
    Vda5050.State state = new Vda5050.State(1, Vda5050.now(), Vda5050.VERSION, Vda5050.MANUFACTURER, "FL-01",
        TASK.toString(), 0, "PARK-01", 0, false, false, false, "AUTOMATIC",
        new Vda5050.Position(11, -6, 0, "linz", true), new Vda5050.PowerSupply(80, false),
        List.of(), List.of(), List.of(), instantActions, List.of(), new Vda5050.SafetyState("NONE", false));
    return new MqttMessage(mapper.writeValueAsBytes(state));
  }

  @Test void treatsStateForAParkMoveAsNormalRatherThanAsAnUnknownTask() throws Exception {
    // A park or charge move is a real VDA order with no transport task behind it. The
    // vehicle reports state against it for the length of the drive, and every one of
    // those used to be rejected as an unknown task -- 5,704 rejections in three hours on
    // the reference stack, each logged at WARN and published as AGV_MESSAGE_REJECTED, so
    // the operator view showed a protocol failure whenever the vehicle went to charge.
    // The exception also aborted the handler, so AGV_UPDATED was skipped with it.
    when(store.isHousekeepingOrder(TASK.toString())).thenReturn(true);
    MqttGateway gateway = gateway();

    gateway.onState(TOPIC, state(null));

    verify(store, never()).acceptDispatch(any());
    verify(execution, never()).completeIfArrived(any(), any());
    verify(metrics, never()).mqttRejected(any());
  }

  @Test void stillRejectsStateForAnOrderItNeverIssued() throws Exception {
    // The other half of the guarantee: an order with neither a task nor a housekeeping
    // record is genuinely unknown and must stay an observable rejection.
    when(store.isHousekeepingOrder(TASK.toString())).thenReturn(false);
    doThrow(new IllegalArgumentException("Unknown task: " + TASK)).when(store).acceptDispatch(TASK);
    MqttGateway gateway = gateway();

    gateway.onState(TOPIC, state(null));

    verify(metrics).mqttRejected(TOPIC);
  }

  @Test void cancelsAnOrderOnlyOnceNoMatterHowOftenTheVehicleRepeatsTheFinishedAction() throws Exception {
    // A state message is a snapshot: the vehicle keeps reporting a finished
    // cancelOrder in every later message. Re-cancelling on each one cancelled every
    // subsequent order within a state tick, so the fleet looped between dispatch and
    // cancellation and never left its bay.
    MqttGateway gateway = gateway();
    MqttMessage repeated = state("a69e940c-a88c-4a94-952c-a0d49ab570bf");

    for (int tick = 0; tick < 12; tick++) gateway.onState(TOPIC, repeated);

    verify(execution, times(1)).cancelled(TASK);
  }

  @Test void cancelsAgainWhenMasterControlIssuesAFreshCancellation() throws Exception {
    MqttGateway gateway = gateway();

    gateway.onState(TOPIC, state("11111111-1111-1111-1111-111111111111"));
    gateway.onState(TOPIC, state("22222222-2222-2222-2222-222222222222"));

    verify(execution, times(2)).cancelled(TASK);
  }

  @Test void leavesAnUncancelledOrderRunning() throws Exception {
    MqttGateway gateway = gateway();

    gateway.onState(TOPIC, state(null));

    verify(execution, never()).cancelled(any());
    verify(store).acceptDispatch(TASK);
  }

  @Test void keepsTheLivePoseInsteadOfRewindingToTheLastPersistedRow() throws Exception {
    // The database row lags the pose by up to one persistLivePose tick, so echoing it
    // back on every state message rewound the vehicle and cancelled out the client's
    // interpolation.
    MqttGateway gateway = gateway();
    gateway.onVisualization("vda5050/v3/demo/FL-01/visualization", visualization(-8, 10, 1.2));

    gateway.onState(TOPIC, state(null));
    gateway.persistLivePose();

    // Lifecycle columns still come from the row; only the pose must survive.
    verify(store).updateAgvMotion(eq("FL-01"), eq(-8d), eq(10d), eq(0d), eq(1.2d), any(), any());
  }

  private MqttMessage visualization(double x, double z, double speed) throws Exception {
    Vda5050.Visualization telemetry = new Vda5050.Visualization(1, Vda5050.now(), Vda5050.VERSION,
        Vda5050.MANUFACTURER, "FL-01", 1, new Vda5050.Position(x, z, 0, "linz", true),
        new Vda5050.Velocity(speed, 0, 0));
    return new MqttMessage(mapper.writeValueAsBytes(telemetry));
  }
}
