package com.example.warehouse.mqtt;

/** MQTT byte transport with explicit QoS and retained-message semantics. */
public final class MqttPublisher {
  private final MqttConnection connection;

  public MqttPublisher(MqttConnection connection) {
    this.connection = connection;
  }

  public void publish(String topic, byte[] payload, int qos, boolean retained) throws Exception {
    connection.client().publish(topic, payload, qos, retained);
  }
}
