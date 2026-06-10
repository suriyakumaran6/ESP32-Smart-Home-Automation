# ESP32 Smart Home Automation - Setup Guide

This guide explains how to set up and run the ESP32 Smart Home Automation system with Android app and MQTT communication.

---

## Project Structure

ESP32-Smart-Home-Automation/
├── ESP32-code/
├── Android-app/
├── Images/
├── Docs/

---

## Requirements

### Hardware:
- ESP32 Dev Board
- 4-Channel Relay Module
- Jumper Wires
- Breadboard
- Wi-Fi Connection

### Software:
- Arduino IDE / PlatformIO
- Android Studio
- MQTT Broker (HiveMQ / Mosquitto)

---

## ESP32 Setup

1. Install libraries:
- WiFi.h
- PubSubClient.h
- ArduinoJson.h

2. Upload code:
esp32-code/smart_home_esp32.ino

Board: ESP32 Dev Module

---

## MQTT Configuration

const char* ssid = "YOUR_WIFI_NAME";
const char* password = "YOUR_WIFI_PASSWORD";
const char* mqtt_server = "broker.hivemq.com";

---

## Android App Setup

1. Open android-app in Android Studio
2. Sync Gradle
3. Run on device/emulator

---

## Communication Flow

Android App → MQTT Broker → ESP32 → Relay

---

## Testing

- Toggle switches in app
- Check Serial Monitor
- Verify relay response

---
