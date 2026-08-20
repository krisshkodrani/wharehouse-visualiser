package com.example.warehouse.mqtt;

import com.example.warehouse.config.WarehouseProperties;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/** Owns broker connection lifecycle and reconnect configuration. */
public final class MqttConnection {
  private final MqttClient client;
  private final MqttConnectOptions options;

  public MqttConnection(WarehouseProperties properties) throws Exception {
    client = new MqttClient(properties.mqttUrl(), "warehouse-backend", new MemoryPersistence());
    options = new MqttConnectOptions();
    options.setAutomaticReconnect(true);
    options.setCleanSession(true);
    options.setUserName(properties.mqttUser());
    options.setPassword(properties.mqttPassword().toCharArray());
    options.setConnectionTimeout(5);
  }

  public boolean isConnected() {
    return client.isConnected();
  }

  public void connect() throws Exception {
    client.connect(options);
  }

  public void disconnect() throws Exception {
    if (client.isConnected()) client.disconnect();
  }

  public void close() throws Exception {
    disconnect();
    client.close();
  }

  MqttClient client() {
    return client;
  }
}
