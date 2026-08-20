package com.example.warehouse.simulator.mqtt;

import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/** Paho transport adapter; it owns broker connection state but no VDA semantics. */
public final class SimulatorMqttClient {
  private final MqttClient client;
  private final MqttConnectOptions options;

  public SimulatorMqttClient(String url, String clientId, String user, String password,
      String willTopic, byte[] willPayload) throws Exception {
    client = new MqttClient(url, clientId, new MemoryPersistence());
    options = new MqttConnectOptions();
    options.setCleanSession(true);
    options.setAutomaticReconnect(true);
    options.setUserName(user);
    options.setPassword(password.toCharArray());
    options.setConnectionTimeout(10);
    options.setWill(willTopic, willPayload, 1, true);
  }

  public void setCallback(MqttCallbackExtended callback) {
    client.setCallback(callback);
  }

  public void connect() throws Exception {
    client.connect(options);
  }

  public void subscribe(String[] topics, int[] qos) throws Exception {
    client.subscribe(topics, qos);
  }

  public void publish(String topic, byte[] payload, int qos, boolean retained) throws Exception {
    client.publish(topic, payload, qos, retained);
  }

  public boolean isConnected() {
    return client.isConnected();
  }

  public void disconnect() throws Exception {
    client.disconnect();
  }

  public void close() throws Exception {
    client.close();
  }
}
