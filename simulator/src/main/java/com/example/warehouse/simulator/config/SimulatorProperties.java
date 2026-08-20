package com.example.warehouse.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed broker and vehicle-dynamics configuration for one simulator process. */
@ConfigurationProperties(prefix = "warehouse")
public record SimulatorProperties(
    String mqttUrl,
    String mqttUser,
    String mqttPassword,
    String vehicleId,
    double speed,
    double loadedSpeed,
    double dockingSpeed,
    double acceleration,
    double braking,
    double batteryConsumptionPerMetre,
    double forkLiftSpeed,
    double forkExtensionSpeed,
    double chargePerMinute,
    int telemetryIntervalMillis) {}
