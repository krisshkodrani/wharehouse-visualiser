package com.example.warehouse.api;

import com.example.warehouse.ApiModels;
import com.example.warehouse.api.dto.AgvResponse;
import com.example.warehouse.api.dto.RuntimeResponse;
import com.example.warehouse.api.dto.ScenarioResponse;
import com.example.warehouse.api.dto.TransportOrderResponse;
import com.example.warehouse.api.dto.TransportTaskResponse;
import com.example.warehouse.api.dto.WarehouseSnapshotResponse;

public final class ApiDtoMapper {
  private ApiDtoMapper() {}

  public static WarehouseSnapshotResponse snapshot(ApiModels.WarehouseSnapshot source) {
    return new WarehouseSnapshotResponse(source.id(), source.name(), source.width(), source.depth(),
        source.racks(), source.locations(), source.loads(), source.agvs().stream().map(ApiDtoMapper::agv).toList(),
        source.jobs(), source.transportOrders().stream().map(ApiDtoMapper::transportOrder).toList(),
        source.tasks().stream().map(ApiDtoMapper::task).toList(), scenario(source.scenario()), runtime(source.runtime()),
        source.conveyorTransfers(), source.obstacles(), source.cartons(), source.robotCells(), source.aisles());
  }

  public static AgvResponse agv(ApiModels.AgvView source) {
    return new AgvResponse(source.id(), source.x(), source.z(), source.theta(), source.velocity(), source.battery(),
        source.status(), source.taskId(), source.charging(), source.currentStationId(), source.handlingPhase(),
        source.forkHeight(), source.forkExtension(), source.carriedLoadId());
  }

  public static TransportTaskResponse task(ApiModels.TransportTaskView source) {
    return new TransportTaskResponse(source.id(), source.transportOrderId(), source.sequence(), source.loadId(),
        source.source(), source.destination(), source.status(), source.route(), source.assignedAgvId(),
        source.acceptedAt(), source.startedAt(), source.completedAt(), source.error());
  }

  public static TransportOrderResponse transportOrder(ApiModels.TransportOrderView source) {
    return new TransportOrderResponse(source.id(), source.type(), source.priority(), source.status(), source.objective(),
        source.scenarioId(), source.error(), source.createdAt(), source.completedAt(),
        source.tasks().stream().map(ApiDtoMapper::task).toList(), source.vdaDispatches(), source.executionEvents());
  }

  public static ScenarioResponse scenario(ApiModels.ScenarioView source) {
    return new ScenarioResponse(source.id(), source.name(), source.configured());
  }

  public static RuntimeResponse runtime(ApiModels.RuntimeView source) {
    return new RuntimeResponse(source.operationState(), source.simulationEpoch(), source.timeScale(),
        source.scenarioId(), source.scenarioConfigured(), source.changedAt());
  }
}
