# ESP32 Smart Home Automation using MQTT

## Project Overview

This project is a smart home automation system developed using ESP32 and MQTT protocol for remotely controlling appliances through Wi-Fi connectivity.

The system uses HiveMQ as the MQTT broker and an Android application for sending control commands.

## Features

* Remote appliance control using Wi-Fi
* MQTT-based communication
* Android mobile application
* Real-time relay switching
* IoT-based smart automation

## Technologies Used

* ESP32
* MQTT
* HiveMQ
* Arduino IDE
* Java (Android)
* Wireshark (Testing)

## Components Used

* ESP32 Development Board
* Relay Module
* Bulb/Fan Load
* Jumper Wires
* Wi-Fi Network

## Working Principle

1. The Android application publishes MQTT messages.
2. HiveMQ broker transfers the messages.
3. ESP32 subscribes to MQTT topics.
4. Relays are triggered based on received commands.
5. Connected appliances are controlled remotely.

## Skills Demonstrated

* IoT Development
* Embedded Systems
* MQTT Communication
* Networking Fundamentals
* Android App Development
* Problem Solving

## Future Improvements

* Voice assistant integration
* Sensor-based automation
* Secure MQTT communication
* Mobile notifications

## Author

Suriya Kumaran A
