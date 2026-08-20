package com.example.warehouse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed backend configuration mapped from the existing `warehouse` YAML namespace. */
@ConfigurationProperties(prefix = "warehouse")
public record WarehouseProperties(
    String mqttUrl,
    String mqttUser,
    String mqttPassword,
    String aiProvider,
    String openrouterApiKey,
    String openrouterModel,
    String openrouterProvider,
    int openrouterTimeoutSeconds) {}
