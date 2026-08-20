package com.example.warehouse.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransportTaskResponse(UUID id, UUID transportOrderId, int sequence, String loadId,
    String source, String destination, String status, List<String> route, String assignedAgvId,
    Instant acceptedAt, Instant startedAt, Instant completedAt, String error) {}
