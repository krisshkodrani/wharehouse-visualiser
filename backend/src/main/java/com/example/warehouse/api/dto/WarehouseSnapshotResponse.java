package com.example.warehouse.api.dto;

import com.example.warehouse.ApiModels;
import java.util.List;

public record WarehouseSnapshotResponse(String id, String name, double width, double depth,
    List<ApiModels.RackView> racks, List<ApiModels.LocationView> locations, List<ApiModels.LoadView> loads,
    List<AgvResponse> agvs, List<ApiModels.JobView> jobs, List<TransportOrderResponse> transportOrders,
    List<TransportTaskResponse> tasks, ScenarioResponse scenario, RuntimeResponse runtime,
    List<ApiModels.ConveyorTransferView> conveyorTransfers, List<ApiModels.ObstacleView> obstacles,
    List<ApiModels.CartonView> cartons, List<ApiModels.RobotCellView> robotCells, List<ApiModels.AisleView> aisles) {}
