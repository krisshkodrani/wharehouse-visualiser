package com.example.warehouse.simulator.mqtt;

import com.example.warehouse.vda.Vda5050;

/** Vehicle-specific MQTT topics subscribed by a simulator instance. */
public record TopicSubscriptions(String order, String instantActions, String control) {
  public static TopicSubscriptions forVehicle(String serialNumber) {
    String prefix = Vda5050.topicPrefix(serialNumber);
    return new TopicSubscriptions(prefix + "/order", prefix + "/instantActions", prefix + "/control");
  }

  public String[] topics() {
    return new String[] {order, instantActions, control};
  }

  public int[] qos() {
    return new int[] {1, 1, 1};
  }
}
