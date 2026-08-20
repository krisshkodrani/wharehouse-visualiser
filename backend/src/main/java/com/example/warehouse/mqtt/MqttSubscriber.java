package com.example.warehouse.mqtt;

import org.eclipse.paho.client.mqttv3.IMqttMessageListener;

/** MQTT subscription transport; interpretation remains in protocol handlers. */
public final class MqttSubscriber {
  private final MqttConnection connection;

  public MqttSubscriber(MqttConnection connection) {
    this.connection = connection;
  }

  public void subscribe(String topic, int qos, IMqttMessageListener listener) throws Exception {
    connection.client().subscribe(topic, qos, listener);
  }
}
