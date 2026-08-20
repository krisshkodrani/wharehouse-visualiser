package com.example.warehouse.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CreateTransportOrderRequest(@NotBlank String type, @NotBlank String priority,
    @NotEmpty List<String> loadIds, String objective) {}
