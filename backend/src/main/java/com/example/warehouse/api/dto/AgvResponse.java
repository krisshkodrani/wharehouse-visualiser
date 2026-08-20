package com.example.warehouse.api.dto;

import java.util.UUID;

public record AgvResponse(String id, double x, double z, double theta, double velocity, double battery,
    String status, UUID taskId, boolean charging, String currentStationId, String handlingPhase,
    double forkHeight, double forkExtension, String carriedLoadId) {}
