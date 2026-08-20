package com.example.warehouse.api.dto;

import com.example.warehouse.ApiModels;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransportOrderResponse(UUID id, String type, String priority, String status, String objective,
    String scenarioId, String error, Instant createdAt, Instant completedAt,
    List<TransportTaskResponse> tasks, List<ApiModels.VdaDispatchView> vdaDispatches,
    List<ApiModels.ExecutionEventView> executionEvents) {}
