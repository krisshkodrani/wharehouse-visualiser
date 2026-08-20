package com.example.warehouse.simulator.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import org.eclipse.paho.client.mqttv3.MqttMessage;

/** Decodes simulator control messages and owns clock-control semantics. */
public final class SimulationControl {
  public record Command(String name, long epoch, Map<String, Object> values) {}

  private final ObjectMapper mapper;
  private final SimulationClock clock;

  public SimulationControl(ObjectMapper mapper, SimulationClock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  @SuppressWarnings("unchecked")
  public Command apply(MqttMessage message) throws Exception {
    Map<String, Object> values = mapper.readValue(message.getPayload(), Map.class);
    String name = String.valueOf(values.get("command"));
    long epoch = ((Number) values.getOrDefault("epoch", clock.epoch())).longValue();
    clock.setTimeScale(((Number) values.getOrDefault("timeScale", clock.timeScale())).intValue());
    if ("PAUSE".equals(name)) clock.pause();
    if ("RESUME".equals(name)) clock.resume(epoch);
    if ("SET_TIME_SCALE".equals(name))
      clock.setTimeScale(((Number) values.getOrDefault("timeScale", 2)).intValue());
    return new Command(name, epoch,
        Collections.unmodifiableMap(new LinkedHashMap<>(values)));
  }
}
