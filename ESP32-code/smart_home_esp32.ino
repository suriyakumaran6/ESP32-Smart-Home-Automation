/************************************************************************
 * ESP32 SMART HOME - MQTT SYSTEM v2.1
 
 * Features:
 * ✓ MQTT Control (HiveMQ Cloud with TLS)
 * ✓ Physical Switch Control with Debouncing
 * ✓ Server Keepalive (Prevents Render Sleep)
 * ✓ Web OTA Interface (Port 8080)
 * ✓ Arduino OTA
 * ✓ Flash Persistence
 * ✓ Auto WiFi/MQTT Reconnect with Backoff
 ************************************************************************/
/************************************************************************
*Additional Experimental Features:

 * ✓ Experimental MQTT Audio Playback       
 * ✓ Prototype Audio Queue Handling    
 * Audio Flow:
 * Android App → AI Server → MQTT → ESP32 Speaker  
************************************************************************/

#include <WiFi.h>
#include <PubSubClient.h>
#include <Preferences.h>
#include <WebServer.h>
#include <Update.h>
#include <ArduinoOTA.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include "driver/i2s.h"
#include <vector>

/**************** CONFIGURATION ****************/
const char* SSID = "YOUR_WIFI_NAME";
const char* WIFI_PASS = "YOUR_WIFI_PASSWORD";

const char* MQTT_SERVER = "4348e232bb5545858370951f4fe2d907.s1.eu.hivemq.cloud";
const int MQTT_PORT = 8883;
const char* MQTT_USER = "YOUR_MQTT_USERNAME";
const char* MQTT_PASS = "YOUR_MQTT_PASSWORD";

// ADD YOUR RENDER SERVER URL HERE
const char* SERVER_URL = "YOUR_SERVER_URL";

const char* ESP_TOKEN = "YOUR_DEVICE_TOKEN";

#define RELAY_ON LOW
#define RELAY_OFF HIGH

/**************** PIN CONFIGURATION ****************/
#define FAN1_PIN 18
#define FAN2_PIN 19
#define LIGHT_PIN 21
#define TV_PIN 5

#define SW_FAN1 32
#define SW_FAN2 33
#define SW_LIGHT 25
#define SW_TV 26

#define SPK_DATA 22
#define I2S_BCLK 14
#define I2S_LRCK 27

/**************** TIMING CONSTANTS ****************/
#define DEBOUNCE_MS 50
#define FAN1_DEBOUNCE_MS 200
#define MQTT_RECONNECT_BASE_MS 5000
#define MQTT_MAX_BACKOFF_MS 60000
#define WIFI_RETRY_INTERVAL 5000
#define WIFI_CONNECT_TIMEOUT 120000
#define FLASH_WRITE_INTERVAL 30000UL
#define STATUS_PUBLISH_INTERVAL 300000UL
#define SERVER_KEEPALIVE_INTERVAL 600000UL  // 10 minutes

/**************** AUDIO SETTINGS ****************/
#define SAMPLE_RATE 16000
#define MAX_AUDIO_QUEUE 3
#define MAX_AUDIO_CHUNK_SIZE 16384
#define I2S_WRITE_TIMEOUT_MS 5000

/**************** AUDIO QUEUE STRUCTURE ****************/
struct AudioChunk {
    uint8_t* data;
    size_t size;
};

std::vector<AudioChunk> audioQueue;  // Audio queue limited to prevent ESP32 RAM exhaustion
bool isPlayingAudio = false;

/**************** GLOBAL OBJECTS ****************/
WiFiClientSecure espClient;
PubSubClient mqtt(espClient);
Preferences prefs;
WebServer server(8080);

/**************** DEVICE STRUCTURE ****************/
struct Device {
    const char* name;
    int relay;
    int sw;
    bool state;
    bool stateChanged;
    bool lastSwState;
    unsigned long lastSwChange;
};

Device devices[] = {
    {"fan1", FAN1_PIN, SW_FAN1, false, false, HIGH, 0},
    {"fan2", FAN2_PIN, SW_FAN2, false, false, HIGH, 0},
    {"light", LIGHT_PIN, SW_LIGHT, false, false, HIGH, 0},
    {"tv", TV_PIN, SW_TV, false, false, HIGH, 0}
};

/**************** GLOBAL STATE ****************/
unsigned long lastWifiTry = 0;
unsigned long lastMqttTry = 0;
unsigned long lastFlashWrite = 0;
unsigned long lastStatusPublish = 0;
unsigned long lastServerKeepalive = 0;
unsigned long wifiDownStart = 0;

int mqttReconnectAttempts = 0;
unsigned long mqttReconnectDelay = MQTT_RECONNECT_BASE_MS;

bool mqttConnected = false;
bool initialStatePublished = false;

/************************************************************************
 * I2S SPEAKER INITIALIZATION
 ************************************************************************/
void initSpeaker() {
    i2s_config_t cfg = {
        .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX),
        .sample_rate = SAMPLE_RATE,
        .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
        .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
        .communication_format = I2S_COMM_FORMAT_I2S,
        .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
        .dma_buf_count = 8,
        .dma_buf_len = 512,
        .use_apll = false,
        .tx_desc_auto_clear = true,
        .fixed_mclk = 0
    };
    
    i2s_pin_config_t pins = {
        .bck_io_num = I2S_BCLK,
        .ws_io_num = I2S_LRCK,
        .data_out_num = SPK_DATA,
        .data_in_num = -1
    };
    
    i2s_driver_install(I2S_NUM_1, &cfg, 0, NULL);
    i2s_set_pin(I2S_NUM_1, &pins);
    i2s_zero_dma_buffer(I2S_NUM_1);
    
    Serial.println(F("[AUDIO] Speaker initialized (I2S)"));
}

/************************************************************************
 * AUDIO QUEUE MANAGEMENT
 ************************************************************************/
bool hasEnoughRAM(size_t required) {
    size_t freeHeap = ESP.getFreeHeap();
    size_t minFreeHeap = 40000;
    return (freeHeap - required) > minFreeHeap;
}

void addToAudioQueue(uint8_t* data, size_t size) {
    if (size > MAX_AUDIO_CHUNK_SIZE) {
        Serial.printf("[AUDIO] Chunk too large: %d bytes (max: %d)\n", size, MAX_AUDIO_CHUNK_SIZE);
        return;
    }
    
    if (!hasEnoughRAM(size)) {
        Serial.printf("[AUDIO] Insufficient RAM (free: %d, needed: %d)\n", ESP.getFreeHeap(), size);
        return;
    }
    
    if (audioQueue.size() >= MAX_AUDIO_QUEUE) {
        Serial.println(F("[AUDIO] Queue full, dropping oldest"));
        free(audioQueue[0].data);
        audioQueue.erase(audioQueue.begin());
    }
    
    uint8_t* audioCopy = (uint8_t*)malloc(size);
    if (audioCopy) {
        memcpy(audioCopy, data, size);
        AudioChunk chunk = {audioCopy, size};
        audioQueue.push_back(chunk);
        Serial.printf("[AUDIO] Added to queue (size: %d, queue: %d, free RAM: %d)\n", 
                     size, audioQueue.size(), ESP.getFreeHeap());
    } else {
        Serial.printf("[AUDIO] Failed to allocate %d bytes (free: %d)\n", size, ESP.getFreeHeap());
    }
}

void playAudioQueue() {
    if (isPlayingAudio || audioQueue.empty()) return;
    
    isPlayingAudio = true;
    AudioChunk chunk = audioQueue[0];
    audioQueue.erase(audioQueue.begin());
    
    Serial.printf("[AUDIO] Playing %d bytes (queue: %d)\n", chunk.size, audioQueue.size());
    
    size_t offset = 0;
    unsigned long startTime = millis();
    
    while (offset < chunk.size) {
        if (millis() - startTime > I2S_WRITE_TIMEOUT_MS) {
            Serial.println(F("[AUDIO] I2S write timeout, aborting"));
            break;
        }
        
        size_t chunkSize = min((size_t)1024, chunk.size - offset);
        size_t written = 0;
        
        esp_err_t result = i2s_write(I2S_NUM_1, chunk.data + offset, chunkSize, &written, 
                                     pdMS_TO_TICKS(100));
        
        if (result != ESP_OK) {
            Serial.printf("[AUDIO] I2S write error: %d\n", result);
            break;
        }
        
        if (written == 0) {
            Serial.println(F("[AUDIO] I2S busy, waiting..."));
            delay(10);
            continue;
        }
        
        offset += written;
    }
    
    free(chunk.data);
    i2s_zero_dma_buffer(I2S_NUM_1);
    
    isPlayingAudio = false;
    Serial.println(F("[AUDIO] Playback complete"));
}

void clearAudioQueue() {
    for (auto& chunk : audioQueue) {
        free(chunk.data);
    }
    audioQueue.clear();
    Serial.println(F("[AUDIO] Queue cleared"));
}

/************************************************************************
 * SERVER KEEPALIVE (PREVENTS RENDER SLEEP)
 ************************************************************************/
void pingServer(unsigned long now) {
    // Only ping if WiFi is connected and enough time has passed
    if (WiFi.status() != WL_CONNECTED) return;
    if (now - lastServerKeepalive < SERVER_KEEPALIVE_INTERVAL) return;
    
    lastServerKeepalive = now;
    
    Serial.println(F("[KEEPALIVE] Pinging server to prevent sleep..."));
    
    HTTPClient http;
    WiFiClientSecure client;
    client.setInsecure();  // Skip SSL verification (Render has valid cert)
    
    http.begin(client, SERVER_URL);
    http.setTimeout(5000);  // 5 second timeout
    
    int httpCode = http.GET();
    
    if (httpCode > 0) {
        if (httpCode == HTTP_CODE_OK) {
            Serial.println(F("[KEEPALIVE] ✓ Server is awake"));
        } else {
            Serial.printf("[KEEPALIVE] Server responded with code: %d\n", httpCode);
        }
    } else {
        Serial.printf("[KEEPALIVE] ✗ Failed: %s\n", http.errorToString(httpCode).c_str());
    }
    
    http.end();
}

/************************************************************************
 * MQTT CALLBACK
 ************************************************************************/
void mqttCallback(char* topic, byte* payload, unsigned int len) {
    const char* topicPrefix = "home/audio/";
    
    if (strncmp(topic, topicPrefix, strlen(topicPrefix)) == 0) {
        Serial.printf("[MQTT AUDIO] Received %d bytes on %s\n", len, topic);
        addToAudioQueue(payload, len);
        return;
    }
    
    char msg[16];
    if (len > 15) len = 15;
    memcpy(msg, payload, len);
    msg[len] = '\0';

    Serial.printf("[MQTT] %s = %s\n", topic, msg);

    if (strcmp(topic, "home/status/request") == 0) {
        for (int i = 0; i < 4; i++) {
            char deviceTopic[32];
            snprintf(deviceTopic, sizeof(deviceTopic), "home/%s", devices[i].name);
            mqtt.publish(deviceTopic, devices[i].state ? "1" : "0", false);
        }
        mqtt.publish("home/status", "1", true);
        Serial.println(F("[MQTT] Status published"));
        return;
    }

    if (strcmp(msg, "0") != 0 && strcmp(msg, "1") != 0) return;

    for (int i = 0; i < 4; i++) {
        char deviceTopic[32];
        snprintf(deviceTopic, sizeof(deviceTopic), "home/%s", devices[i].name);
        
        if (strcmp(deviceTopic, topic) == 0) {
            bool newState = (strcmp(msg, "1") == 0);
            if (devices[i].state != newState) {
                devices[i].state = newState;
                devices[i].stateChanged = true;
                digitalWrite(devices[i].relay, newState ? RELAY_ON : RELAY_OFF);
                Serial.printf("[MQTT] %s -> %s\n", devices[i].name, newState ? "ON" : "OFF");
            }
            break;
        }
    }
}

/************************************************************************
 * WIFI & MQTT CONNECTION
 ************************************************************************/
void connectWiFi() {
    if (WiFi.status() == WL_CONNECTED) return;
    if (millis() - lastWifiTry < WIFI_RETRY_INTERVAL) return;
    
    lastWifiTry = millis();
    Serial.printf("[WIFI] Connecting to %s...\n", SSID);
    WiFi.begin(SSID, WIFI_PASS);
}

void connectMQTT() {
    if (mqtt.connected() || WiFi.status() != WL_CONNECTED) return;
    if (millis() - lastMqttTry < mqttReconnectDelay) return;
    
    lastMqttTry = millis();

    char clientId[32];
    snprintf(clientId, sizeof(clientId), "ESP32_%08X", (uint32_t)ESP.getEfuseMac());
    
    if (mqtt.connect(clientId, MQTT_USER, MQTT_PASS)) {
        mqttConnected = true;
        mqttReconnectAttempts = 0;
        mqttReconnectDelay = MQTT_RECONNECT_BASE_MS;
        
        Serial.println(F("[MQTT] Connected"));

        for (int i = 0; i < 4; i++) {
            char deviceTopic[32];
            snprintf(deviceTopic, sizeof(deviceTopic), "home/%s", devices[i].name);
            mqtt.subscribe(deviceTopic, 0);
        }
        mqtt.subscribe("home/status/request", 0);
        mqtt.subscribe("home/audio/#", 1);
        Serial.println(F("[MQTT] Subscribed to home/audio/#"));

        if (!initialStatePublished) {
            for (int i = 0; i < 4; i++) {
                char deviceTopic[32];
                snprintf(deviceTopic, sizeof(deviceTopic), "home/%s", devices[i].name);
                mqtt.publish(deviceTopic, devices[i].state ? "1" : "0", true);
            }
            initialStatePublished = true;
        }
        
        lastStatusPublish = millis();
    } else {
        mqttConnected = false;
        mqttReconnectAttempts++;
        mqttReconnectDelay = min(MQTT_RECONNECT_BASE_MS * (1 << mqttReconnectAttempts), 
                                 MQTT_MAX_BACKOFF_MS);
        
        Serial.printf("[MQTT] Failed, rc=%d (attempt %d, retry in %lu ms)\n", 
                     mqtt.state(), mqttReconnectAttempts, mqttReconnectDelay);
    }
}

void publishStatus(unsigned long now) {
    if (!mqtt.connected()) return;
    if (now - lastStatusPublish < STATUS_PUBLISH_INTERVAL) return;
    
    lastStatusPublish = now;
    mqtt.publish("home/status", "1", false);
    Serial.println(F("[MQTT] Heartbeat"));
}

/************************************************************************
 * PHYSICAL SWITCH HANDLING
 ************************************************************************/
void handleSwitches(unsigned long now) {
    for (int i = 0; i < 4; i++) {
        bool currentSwState = digitalRead(devices[i].sw);
        unsigned long debounce = (i == 0) ? FAN1_DEBOUNCE_MS : DEBOUNCE_MS;
        
        if (currentSwState != devices[i].lastSwState) {
            if (now - devices[i].lastSwChange >= debounce) {
                devices[i].lastSwState = currentSwState;
                devices[i].lastSwChange = now;
                
                bool desiredState = (currentSwState == LOW);

                if (devices[i].state != desiredState) {
                    devices[i].state = desiredState;
                    devices[i].stateChanged = true;
                    digitalWrite(devices[i].relay, desiredState ? RELAY_ON : RELAY_OFF);
                    
                    if (mqtt.connected()) {
                        char deviceTopic[32];
                        snprintf(deviceTopic, sizeof(deviceTopic), "home/%s", devices[i].name);
                        mqtt.publish(deviceTopic, desiredState ? "1" : "0", true);
                    }
                    
                    Serial.printf("[SWITCH] %s -> %s\n", devices[i].name, desiredState ? "ON" : "OFF");
                }
            }
        }
    }
}

/************************************************************************
 * FLASH PERSISTENCE
 ************************************************************************/
void writeFlash(unsigned long now) {
    if ((now >= lastFlashWrite && (now - lastFlashWrite) < FLASH_WRITE_INTERVAL) ||
        (now < lastFlashWrite && ((ULONG_MAX - lastFlashWrite) + now) < FLASH_WRITE_INTERVAL)) {
        return;
    }
    
    lastFlashWrite = now;
    bool anyChanged = false;

    for (int i = 0; i < 4; i++) {
        if (devices[i].stateChanged) {
            if (prefs.putBool(devices[i].name, devices[i].state)) {
                devices[i].stateChanged = false;
                anyChanged = true;
            } else {
                Serial.printf("[FLASH] Failed to write %s\n", devices[i].name);
            }
        }
    }

    if (anyChanged) Serial.println(F("[FLASH] States saved"));
}

/************************************************************************
 * WEB SERVER HANDLERS
 ************************************************************************/
void handleRoot() {
    String html = F("<!DOCTYPE html><html><head><title>ESP32 Smart Home</title>");
    html += F("<meta name='viewport' content='width=device-width,initial-scale=1'>");
    html += F("<style>");
    html += F("body{font-family:Arial;margin:20px;background:#0f0f0f;color:#fff}");
    html += F("h1{color:#4CAF50}");
    html += F(".info{background:#1a1a1a;padding:15px;margin:10px 0;border-radius:8px;border:1px solid #333}");
    html += F(".device{background:#2d2d2d;padding:12px;margin:8px 0;border-radius:6px;display:flex;justify-content:space-between}");
    html += F(".on{border-left:4px solid #4CAF50}");
    html += F(".off{border-left:4px solid #f44336}");
    html += F(".status{font-weight:bold}");
    html += F("form{background:#1a1a1a;padding:15px;border-radius:8px;margin-top:20px}");
    html += F("input[type=file]{color:#fff;margin:10px 0}");
    html += F("input[type=submit]{background:#4CAF50;color:#fff;padding:10px 20px;border:none;border-radius:4px;cursor:pointer}");
    html += F("</style></head><body>");
    
    html += F("<h1>🏠 ESP32 Smart Home - v2.1</h1>");
    
    html += F("<div class='info'>");
    html += F("<strong>IP Address:</strong> ");
    html += WiFi.localIP().toString();
    html += F("<br>");
    html += F("<strong>MQTT:</strong> <span class='status'>");
    html += mqttConnected ? String(F("✓ Connected")) : String(F("✗ Disconnected"));
    html += F("</span><br>");
    html += F("<strong>Free Heap:</strong> ");
    html += String(ESP.getFreeHeap());
    html += F(" bytes<br>");
    html += F("<strong>Audio Queue:</strong> ");
    html += String(audioQueue.size());
    html += F(" / ");
    html += String(MAX_AUDIO_QUEUE);
    html += F("<br>");
    html += F("<strong>Playing:</strong> ");
    html += isPlayingAudio ? String(F("Yes")) : String(F("No"));
    html += F("<br>");
    html += F("<strong>Server Keepalive:</strong> Active (10 min)");
    html += F("</div>");
    
    html += F("<h2>Device Status</h2>");
    
    for (int i = 0; i < 4; i++) {
        html += F("<div class='device ");
        html += devices[i].state ? String(F("on")) : String(F("off"));
        html += F("'>");
        html += F("<span>");
        html += String(devices[i].name);
        html += F("</span>");
        html += F("<span class='status'>");
        html += devices[i].state ? String(F("ON")) : String(F("OFF"));
        html += F("</span>");
        html += F("</div>");
    }
    
    html += F("<h2>Firmware Update</h2>");
    html += F("<form method='POST' action='/update' enctype='multipart/form-data'>");
    html += F("<input type='file' name='update' accept='.bin'><br>");
    html += F("<input type='submit' value='Upload Firmware'>");
    html += F("</form>");
    
    html += F("</body></html>");
    
    server.send(200, "text/html", html);
}

void handleUpdate() {
    server.sendHeader(F("Connection"), F("close"));
    server.send(200, F("text/plain"), (Update.hasError()) ? F("FAIL") : F("OK"));
}

void handleUploadUpdate() {
    HTTPUpload& upload = server.upload();
    
    if (upload.status == UPLOAD_FILE_START) {
        Serial.printf("[OTA] Update: %s\n", upload.filename.c_str());
        if (!Update.begin(UPDATE_SIZE_UNKNOWN)) {
            Update.printError(Serial);
        }
    } else if (upload.status == UPLOAD_FILE_WRITE) {
        if (Update.write(upload.buf, upload.currentSize) != upload.currentSize) {
            Update.printError(Serial);
        }
    } else if (upload.status == UPLOAD_FILE_END) {
        if (Update.end(true)) {
            Serial.printf("[OTA] Success: %u bytes\n", upload.totalSize);
        } else {
            Update.printError(Serial);
        }
    }
}

/************************************************************************
 * SETUP
 ************************************************************************/
void setup() {
    Serial.begin(115200);
    delay(500);

    Serial.println(F("\n========================================"));
    Serial.println(F("ESP32 SMART HOME - v2.1 (WITH KEEPALIVE)"));
    Serial.println(F("========================================"));
    Serial.printf("Free heap: %d bytes\n", ESP.getFreeHeap());

    prefs.begin("relay", false);

    for (int i = 0; i < 4; i++) {
        pinMode(devices[i].relay, OUTPUT);
        pinMode(devices[i].sw, INPUT_PULLUP);
        
        devices[i].state = prefs.getBool(devices[i].name, false);
        devices[i].lastSwState = digitalRead(devices[i].sw);
        
        digitalWrite(devices[i].relay, devices[i].state ? RELAY_ON : RELAY_OFF);
        
        Serial.printf("[DEVICE] %s: %s\n", devices[i].name, devices[i].state ? "ON" : "OFF");
    }

    WiFi.mode(WIFI_STA);
    WiFi.begin(SSID, WIFI_PASS);
    Serial.print(F("[WIFI] Connecting"));
    
    int attempts = 0;
    while (WiFi.status() != WL_CONNECTED && attempts < 40) {
        delay(500);
        Serial.print(F("."));
        attempts++;
    }
    
    if (WiFi.status() == WL_CONNECTED) {
        Serial.println(F("\n[WIFI] Connected!"));
        Serial.print(F("[WIFI] IP: "));
        Serial.println(WiFi.localIP());
    } else {
        Serial.println(F("\n[WIFI] Failed to connect, will retry in background"));
    }

    espClient.setInsecure();
    
    mqtt.setServer(MQTT_SERVER, MQTT_PORT);
    mqtt.setCallback(mqttCallback);
    mqtt.setKeepAlive(120);
    mqtt.setSocketTimeout(10);
    mqtt.setBufferSize(MAX_AUDIO_CHUNK_SIZE + 512);

    initSpeaker();

    server.on("/", handleRoot);
    server.on("/update", HTTP_POST, handleUpdate, handleUploadUpdate);
    server.begin();
    
    Serial.println(F("[WEB] Server started on port 8080"));

    ArduinoOTA.setHostname("ESP32-SmartHome");
    ArduinoOTA.onStart([]() {
        Serial.println(F("[OTA] Start"));
        clearAudioQueue();
    });
    ArduinoOTA.onEnd([]() {
        Serial.println(F("\n[OTA] End"));
    });
    ArduinoOTA.onProgress([](unsigned int progress, unsigned int total) {
        if (total > 0) {
            Serial.printf("[OTA] Progress: %u%%\r", (progress * 100 / total));
        }
    });
    ArduinoOTA.onError([](ota_error_t error) {
        Serial.printf("[OTA] Error[%u]: ", error);
    });
    ArduinoOTA.begin();

    Serial.println(F("\n========================================"));
    Serial.println(F("SYSTEM READY"));
    Serial.println(F("========================================"));
    Serial.println(F("✓ MQTT Control"));
    Serial.println(F("✓ Physical Switches"));
    Serial.println(F("✓ MQTT Audio System"));
    Serial.printf("✓ Audio Queue (%d slots @ %d KB)\n", MAX_AUDIO_QUEUE, MAX_AUDIO_CHUNK_SIZE / 1024);
    Serial.println(F("✓ Server Keepalive (10 min ping)"));
    Serial.print(F("✓ Web Interface (http://"));
    Serial.print(WiFi.localIP());
    Serial.println(F(":8080)"));
    Serial.println(F("✓ Arduino OTA"));
    Serial.println(F("========================================\n"));
}

/************************************************************************
 * MAIN LOOP
 ************************************************************************/
void loop() {
    unsigned long now = millis();

    server.handleClient();
    ArduinoOTA.handle();

    if (WiFi.status() != WL_CONNECTED) {
        connectWiFi();
        
        if (wifiDownStart == 0) {
            wifiDownStart = now;
        } else if (now - wifiDownStart > WIFI_CONNECT_TIMEOUT) {
            Serial.println(F("[WIFI] Timeout - restarting"));
            ESP.restart();
        }
    } else {
        if (wifiDownStart > 0) {
            Serial.print(F("[WIFI] Reconnected: "));
            Serial.println(WiFi.localIP());
            wifiDownStart = 0;
            mqttReconnectAttempts = 0;
            mqttReconnectDelay = MQTT_RECONNECT_BASE_MS;
        }
        connectMQTT();
    }

    if (mqtt.connected()) {
        mqtt.loop();
        publishStatus(now);
    }

    handleSwitches(now);
    writeFlash(now);
    playAudioQueue();
    pingServer(now);  // Keep Render server awake

    delay(10);
}

/*
 * MIT License
 * Copyright (c) 2026 Suriya Kumaran A
 */
