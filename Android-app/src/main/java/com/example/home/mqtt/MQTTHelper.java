package com.example.home.mqtt;

import android.content.Context;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONObject;

public class MQTTHelper {
    private static final String TAG = "MQTTHelper";

    private MqttClient mqttClient;
    private MqttConnectOptions connOptions;
    private MQTTListener listener;

    // Configuration
    private String broker;
    private String clientId;
    private String username;
    private String password;
    private int connectionTimeout = 30;
    private int keepAliveInterval = 60;

    // State
    private boolean isConnected = false;

    public interface MQTTListener {
        void onConnected();
        void onConnectionLost(Throwable cause);
        void onMessageReceived(String topic, String message);
        void onConnectionFailed(Exception e);
    }

    // Constructor
    public MQTTHelper(String broker, String clientId, String username, String password,
                      MQTTListener listener) {
        this.broker = broker;
        this.clientId = clientId;
        this.username = username;
        this.password = password;
        this.listener = listener;
    }

    /**
     * Initialize MQTT connection
     */
    public void connect() {
        try {
            mqttClient = new MqttClient(broker, clientId, null);

            connOptions = new MqttConnectOptions();
            connOptions.setUserName(username);
            connOptions.setPassword(password.toCharArray());
            connOptions.setAutomaticReconnect(true);
            connOptions.setCleanSession(true);
            connOptions.setConnectionTimeout(connectionTimeout);
            connOptions.setKeepAliveInterval(keepAliveInterval);
            connOptions.setMaxInflight(100);

            // Set up callback
            mqttClient.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    isConnected = true;
                    Log.i(TAG, "MQTT Connected: " + serverURI);
                    if (listener != null) {
                        listener.onConnected();
                    }
                }

                @Override
                public void connectionLost(Throwable cause) {
                    isConnected = false;
                    Log.w(TAG, "MQTT Connection Lost", cause);
                    if (listener != null) {
                        listener.onConnectionLost(cause);
                    }
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload());
                    Log.d(TAG, "Message received: " + topic + " = " + payload);
                    if (listener != null) {
                        listener.onMessageReceived(topic, payload);
                    }
                }

                @Override
                public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
                    // Delivery complete
                }
            });

            mqttClient.connect(connOptions);

        } catch (MqttException e) {
            Log.e(TAG, "MQTT Connection Failed", e);
            isConnected = false;
            if (listener != null) {
                listener.onConnectionFailed(e);
            }
        }
    }

    /**
     * Disconnect from MQTT broker
     */
    public void disconnect() {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
                mqttClient.close();
                isConnected = false;
                Log.i(TAG, "MQTT Disconnected");
            }
        } catch (MqttException e) {
            Log.e(TAG, "Error disconnecting", e);
        }
    }

    /**
     * Subscribe to a topic
     */
    public void subscribe(String topic, int qos) {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.subscribe(topic, qos);
                Log.d(TAG, "Subscribed to: " + topic);
            } else {
                Log.w(TAG, "Cannot subscribe - not connected");
            }
        } catch (MqttException e) {
            Log.e(TAG, "Error subscribing to " + topic, e);
        }
    }

    /**
     * Unsubscribe from a topic
     */
    public void unsubscribe(String topic) {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.unsubscribe(topic);
                Log.d(TAG, "Unsubscribed from: " + topic);
            }
        } catch (MqttException e) {
            Log.e(TAG, "Error unsubscribing from " + topic, e);
        }
    }

    /**
     * Publish a message to a topic
     */
    public void publish(String topic, String payload, int qos, boolean retained) {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                MqttMessage message = new MqttMessage(payload.getBytes());
                message.setQos(qos);
                message.setRetained(retained);
                mqttClient.publish(topic, message);
                Log.d(TAG, "Published to " + topic + ": " + payload);
            } else {
                Log.w(TAG, "Cannot publish - not connected");
            }
        } catch (MqttException e) {
            Log.e(TAG, "Error publishing to " + topic, e);
        }
    }

    /**
     * Publish a JSON message
     */
    public void publishJSON(String topic, JSONObject json, int qos, boolean retained) {
        try {
            publish(topic, json.toString(), qos, retained);
        } catch (Exception e) {
            Log.e(TAG, "Error publishing JSON", e);
        }
    }

    /**
     * Send a device command
     */
    public void sendCommand(String device, String command) {
        String topic = "home/" + device;
        String payload = command.toUpperCase().equals("ON") ? "ON" : "OFF";
        publish(topic, payload, 1, false);
    }

    /**
     * Request status update
     */
    public void requestStatus() {
        publish("home/status/request", "", 1, false);
    }

    /**
     * Request detailed status
     */
    public void requestDetailedStatus() {
        publish("home/status/request", "detailed", 1, false);
    }

    /**
     * Check if connected
     */
    public boolean isConnected() {
        return isConnected && mqttClient != null && mqttClient.isConnected();
    }

    /**
     * Get MQTT client (if you need direct access)
     */
    public MqttClient getClient() {
        return mqttClient;
    }

    // Setters for configuration
    public void setConnectionTimeout(int seconds) {
        this.connectionTimeout = seconds;
    }

    public void setKeepAliveInterval(int seconds) {
        this.keepAliveInterval = seconds;
    }
}