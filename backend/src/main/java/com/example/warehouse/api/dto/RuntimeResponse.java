package com.example.warehouse.api.dto;

import java.time.Instant;

public record RuntimeResponse(String operationState, long simulationEpoch, int timeScale,
    String scenarioId, boolean scenarioConfigured, Instant changedAt) {}
