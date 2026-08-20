package com.example.warehouse.mqtt;

import com.example.warehouse.vda.Vda5050;

/** Constructs vehicle-specific VDA topic names in one transport-only boundary. */
public final class TopicFactory {
  private TopicFactory() {}

  public static String order(String vehicleId) { return topic(vehicleId, "order"); }
  public static String instantActions(String vehicleId) { return topic(vehicleId, "instantActions"); }
  public static String state(String vehicleId) { return topic(vehicleId, "state"); }
  public static String visualization(String vehicleId) { return topic(vehicleId, "visualization"); }
  public static String connection(String vehicleId) { return topic(vehicleId, "connection"); }
  public static String handling(String vehicleId) { return topic(vehicleId, "handling"); }
  public static String control(String vehicleId) { return topic(vehicleId, "control"); }

  private static String topic(String vehicleId, String messageType) {
    return Vda5050.topicPrefix(vehicleId) + "/" + messageType;
  }
}
