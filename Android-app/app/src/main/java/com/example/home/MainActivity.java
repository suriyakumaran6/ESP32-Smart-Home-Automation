package com.example.home;

import android.Manifest;
import android.content.res.ColorStateList;
import android.view.View;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.home.databinding.ActivityMainBinding;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.snackbar.Snackbar;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;  // FIX: Added missing import
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SmartHome";

    // ================= MQTT =================
    private static final String MQTT_BROKER =
            "ssl://4348e232bb5545858370951f4fe2d907.s1.eu.hivemq.cloud:8883";

    // SECURITY: Load from BuildConfig in production
    // BuildConfig.MQTT_USER, BuildConfig.MQTT_PASS, BuildConfig.ESP_TOKEN
    private String MQTT_USER = BuildConfig.MQTT_USER; // "Suriya"
    private String MQTT_PASS = BuildConfig.MQTT_PASS; // "Suriya6anbu6@#"
    private String ESP_TOKEN = BuildConfig.ESP_TOKEN; // "9f3a2c7e_dev_voice_ai"

    private static final int SYNC_TIMEOUT_MS = 5000;
    private static final int MAX_SYNC_RETRIES = 3;
    private static final int MAX_MQTT_RETRIES = 10;
    private int mqttRetryCount = 0;

    // ================= VOICE =================
    private static final int SAMPLE_RATE = 16000;
    private static final int RECORD_DURATION_MS = 5000; // Increased to 5 seconds

    private static final String AI_SERVER_URL =
            "https://ai-server-7qry.onrender.com/voice";

    private static final int PERMISSION_REQUEST_CODE = 1001;

    // ================= CORE =================
    private ActivityMainBinding binding;
    private MqttClient mqttClient;
    private ExecutorService executor;
    private Handler mainHandler;
    private SharedPreferences prefs;

    // ================= STATE =================
    private boolean lightOn, fan1On, fan2On, tvOn;
    private boolean isConnected = false;
    private boolean mqttSynced = false;
    private boolean isRecording = false;

    private int syncRetryCount = 0;
    private Runnable syncTimeoutRunnable;

    private AudioRecord audioRecord;
    private Animation micPulseAnimation;
    private View micPulseView;

    // =====================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Get room name from Dashboard
        String room = getIntent().getStringExtra("ROOM_NAME");
        if (room == null) room = "Home";

        if (binding.roomTitle != null) {
            binding.roomTitle.setText(room);
        }

        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("SmartHome", MODE_PRIVATE);

        if (!hasPermissions()) requestPermissions();

        setupClickListeners();
        updateConnectionStatus(false);
        disableAllControls();

        // Initialize mic pulse animation
        micPulseView = findViewById(R.id.micPulse);
        if (micPulseView != null) {
            try {
                micPulseAnimation = AnimationUtils.loadAnimation(this, R.anim.mic_pulse);
            } catch (Exception e) {
                Log.w(TAG, "mic_pulse animation not found", e);
            }
        }

        executor.execute(this::setupMQTT);
    }

    // =====================================================
    // PERMISSIONS
    // =====================================================

    private boolean hasPermissions() {
        return ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                PERMISSION_REQUEST_CODE
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int code, @NonNull String[] perms, @NonNull int[] res) {

        super.onRequestPermissionsResult(code, perms, res);

        if (code == PERMISSION_REQUEST_CODE &&
                res.length > 0 &&
                res[0] == PackageManager.PERMISSION_GRANTED) {

            showSnackbar("Microphone permission granted");
            if (binding.voiceBtn != null) {
                binding.voiceBtn.setEnabled(hasPermissions());
            }
        } else {
            showSnackbar("Microphone permission denied");
            if (binding.voiceBtn != null) {
                binding.voiceBtn.setEnabled(false);
            }
        }
    }

    // =====================================================
    // UI
    // =====================================================

    private void disableAllControls() {
        if (binding.lightCard != null) binding.lightCard.setEnabled(false);
        if (binding.fan1Card != null) binding.fan1Card.setEnabled(false);
        if (binding.fan2Card != null) binding.fan2Card.setEnabled(false);
        if (binding.tvCard != null) binding.tvCard.setEnabled(false);
        if (binding.onBtn != null) binding.onBtn.setEnabled(false);
        if (binding.offBtn != null) binding.offBtn.setEnabled(false);
        if (binding.voiceBtn != null) binding.voiceBtn.setEnabled(false);
    }

    private void enableAllControls() {
        if (binding.lightCard != null) binding.lightCard.setEnabled(true);
        if (binding.fan1Card != null) binding.fan1Card.setEnabled(true);
        if (binding.fan2Card != null) binding.fan2Card.setEnabled(true);
        if (binding.tvCard != null) binding.tvCard.setEnabled(true);
        if (binding.onBtn != null) binding.onBtn.setEnabled(true);
        if (binding.offBtn != null) binding.offBtn.setEnabled(true);
        if (binding.voiceBtn != null) binding.voiceBtn.setEnabled(hasPermissions());
    }

    private void setupClickListeners() {
        if (binding.lightCard != null) {
            binding.lightCard.setOnClickListener(v ->
                    toggleDevice("light", binding.lightLoading));
        }

        if (binding.fan1Card != null) {
            binding.fan1Card.setOnClickListener(v ->
                    toggleDevice("fan1", binding.fan1Loading));
        }

        if (binding.fan2Card != null) {
            binding.fan2Card.setOnClickListener(v ->
                    toggleDevice("fan2", binding.fan2Loading));
        }

        if (binding.tvCard != null) {
            binding.tvCard.setOnClickListener(v ->
                    toggleDevice("tv", binding.tvLoading));
        }

        if (binding.onBtn != null) {
            binding.onBtn.setOnClickListener(v -> allOn());
        }

        if (binding.offBtn != null) {
            binding.offBtn.setOnClickListener(v -> allOff());
        }

        if (binding.voiceBtn != null) {
            binding.voiceBtn.setOnClickListener(v -> {
                if (!hasPermissions()) {
                    requestPermissions();
                    return;
                }

                if (isRecording) {
                    stopVoiceCommand();
                } else {
                    startVoiceCommand();
                }
            });
        }
    }

    // =====================================================
    // MQTT
    // =====================================================

    private void setupMQTT() {
        if (mqttRetryCount >= MAX_MQTT_RETRIES) {
            Log.e(TAG, "Max MQTT retry attempts reached");
            mainHandler.post(() -> {
                showSnackbar("Unable to connect to server");
                updateConnectionStatus(false);
            });
            return;
        }

        try {
            String clientId =
                    "Android_" + UUID.randomUUID().toString().substring(0, 6);

            // FIX #1: Use MemoryPersistence instead of null
            mqttClient = new MqttClient(MQTT_BROKER, clientId, new MemoryPersistence());

            MqttConnectOptions opt = new MqttConnectOptions();
            opt.setUserName(MQTT_USER);
            opt.setPassword(MQTT_PASS.toCharArray());
            opt.setAutomaticReconnect(true);
            opt.setCleanSession(true);
            opt.setConnectionTimeout(10);
            opt.setKeepAliveInterval(30);

            // TODO: For production, add SSL certificate validation
            // opt.setSocketFactory(getSSLSocketFactory());

            mqttClient.setCallback(new MqttCallbackExtended() {

                @Override
                public void connectComplete(boolean reconnect, String uri) {
                    isConnected = true;
                    mqttSynced = false;
                    syncRetryCount = 0;
                    mqttRetryCount = 0;

                    runOnUiThread(() -> {
                        updateConnectionStatus(true);
                        if (binding.voiceBtn != null) {
                            binding.voiceBtn.setEnabled(hasPermissions());
                        }
                    });

                    try {
                        // FIX #6: More specific topic subscription
                        mqttClient.subscribe("home/light", 1);
                        mqttClient.subscribe("home/fan1", 1);
                        mqttClient.subscribe("home/fan2", 1);
                        mqttClient.subscribe("home/tv", 1);
                        mqttClient.subscribe("home/status", 1);

                        mqttClient.publish(
                                "home/status/request",
                                "REQUEST".getBytes(), // Non-empty payload
                                1,
                                true
                        );
                        setSyncTimeout();
                    } catch (Exception e) {
                        Log.e(TAG, "MQTT subscribe error", e);
                    }
                }

                @Override
                public void connectionLost(Throwable cause) {
                    isConnected = false;
                    mqttSynced = false;
                    Log.w(TAG, "MQTT connection lost", cause);
                    runOnUiThread(() -> {
                        updateConnectionStatus(false);
                        disableAllControls();
                        showSnackbar("Connection lost. Reconnecting...");
                    });
                }

                @Override
                public void messageArrived(String topic, MqttMessage msg) {
                    runOnUiThread(() ->
                            updateDeviceState(topic,
                                    new String(msg.getPayload())));
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            mqttClient.connect(opt);

        } catch (Exception e) {
            Log.e(TAG, "MQTT connection error (attempt " + (mqttRetryCount + 1) + ")", e);
            mqttRetryCount++;

            // FIX #8: Safe exponential backoff
            long delayMs = Math.min(3000L * (long)(Math.pow(2, Math.min(mqttRetryCount, 5))), 60000L);
            mainHandler.postDelayed(this::setupMQTT, delayMs);
        }
    }

    private void sendCommand(String topic, String payload, ProgressBar loader) {
        if (!isConnected) {
            showSnackbar("Not connected to server");
            if (loader != null) {
                mainHandler.post(() -> loader.setVisibility(ProgressBar.GONE));
            }
            return;
        }

        executor.execute(() -> {
            try {
                if (mqttClient != null && mqttClient.isConnected()) {
                    mqttClient.publish(topic, payload.getBytes(), 1, true);
                    Log.d(TAG, "Published: " + topic + " = " + payload);
                } else {
                    Log.w(TAG, "MQTT client not connected");
                    mainHandler.post(() -> showSnackbar("Connection lost"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Publish error", e);
                mainHandler.post(() -> showSnackbar("Command failed"));
            } finally {
                if (loader != null)
                    mainHandler.post(() ->
                            loader.setVisibility(ProgressBar.GONE));
            }
        });
    }

    // =====================================================
    // DEVICE STATE
    // =====================================================

    private void updateDeviceState(String topic, String payload) {
        boolean state = payload.equals("1");

        switch (topic) {

            case "home/light":
                if (lightOn != state) {
                    lightOn = state;
                    cache("light", state);
                    updateCard(binding.lightCard, binding.lightIcon, state);
                }
                break;

            case "home/fan1":
                if (fan1On != state) {
                    fan1On = state;
                    cache("fan1", state);
                    updateCard(binding.fan1Card, binding.fan1Icon, state);
                }
                break;

            case "home/fan2":
                if (fan2On != state) {
                    fan2On = state;
                    cache("fan2", state);
                    updateCard(binding.fan2Card, binding.fan2Icon, state);
                }
                break;

            case "home/tv":
                if (tvOn != state) {
                    tvOn = state;
                    cache("tv", state);
                    updateCard(binding.tvCard, binding.tvIcon, state);
                }
                break;

            case "home/status":
                if (!mqttSynced) {
                    mqttSynced = true;
                    enableAllControls();
                    showSnackbar("Connected to devices");
                    // FIX #5: Cancel timeout
                    if (syncTimeoutRunnable != null) {
                        mainHandler.removeCallbacks(syncTimeoutRunnable);
                    }
                }
                break;
        }
    }

    private void setSyncTimeout() {
        // FIX #5: Clear previous timeout
        if (syncTimeoutRunnable != null) {
            mainHandler.removeCallbacks(syncTimeoutRunnable);
        }

        syncTimeoutRunnable = () -> {
            if (!mqttSynced && syncRetryCount++ < MAX_SYNC_RETRIES) {
                try {
                    if (mqttClient != null && mqttClient.isConnected()) {
                        mqttClient.publish(
                                "home/status/request",
                                "REQUEST".getBytes(),
                                1,
                                true
                        );
                        setSyncTimeout();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Sync request error", e);
                }
            } else if (!mqttSynced) {
                loadCachedStates();
                mqttSynced = true;
                enableAllControls();
                showSnackbar("Loaded cached states");
            }
        };
        mainHandler.postDelayed(syncTimeoutRunnable, SYNC_TIMEOUT_MS);
    }

    private void loadCachedStates() {
        lightOn = prefs.getBoolean("lightOn", false);
        fan1On = prefs.getBoolean("fan1On", false);
        fan2On = prefs.getBoolean("fan2On", false);
        tvOn = prefs.getBoolean("tvOn", false);

        updateCard(binding.lightCard, binding.lightIcon, lightOn);
        updateCard(binding.fan1Card, binding.fan1Icon, fan1On);
        updateCard(binding.fan2Card, binding.fan2Icon, fan2On);
        updateCard(binding.tvCard, binding.tvIcon, tvOn);
    }

    private void cache(String dev, boolean s) {
        prefs.edit().putBoolean(dev + "On", s).apply();
    }

    // =====================================================
    // VOICE COMMAND
    // =====================================================

    private void startVoiceCommand() {
        if (!hasPermissions() || isRecording) return;

        if (!isConnected) {
            showSnackbar("Not connected to server");
            return;
        }

        isRecording = true;

        runOnUiThread(() -> {
            if (binding.voiceBtn != null) {
                binding.voiceBtn.setBackgroundTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.mic_active)));
            }

            if (binding.micPulse != null && micPulseAnimation != null) {
                binding.micPulse.setVisibility(View.VISIBLE);
                binding.micPulse.startAnimation(micPulseAnimation);
            }

            if (binding.voiceStatus != null) {
                binding.voiceStatus.setText("🎙 Listening...");
            }
        });

        executor.execute(this::recordAndSendVoice);
    }

    private void stopVoiceCommand() {
        isRecording = false;
        releaseAudioRecord();
        resetVoice();
    }

    // FIX #4: Centralized audio release
    private void releaseAudioRecord() {
        if (audioRecord != null) {
            try {
                if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    audioRecord.stop();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping AudioRecord", e);
            }

            try {
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing AudioRecord", e);
            } finally {
                audioRecord = null;
            }
        }
    }

    private void recordAndSendVoice() {

        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            Log.w(TAG, "RECORD_AUDIO permission not granted");
            resetVoice();
            return;
        }

        try {
            // FIX #3: Use buffer size >= minimum
            int minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size");
                resetVoice();
                return;
            }

            int buf = Math.max(minBuf, 4096); // Use at least 4KB buffer

            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    buf
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized");
                releaseAudioRecord();
                resetVoice();
                return;
            }

            audioRecord.startRecording();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[buf];
            long start = System.currentTimeMillis();

            // Record audio
            while (isRecording &&
                    System.currentTimeMillis() - start < RECORD_DURATION_MS) {
                int r = audioRecord.read(b, 0, b.length);
                if (r > 0) out.write(b, 0, r);
            }

            releaseAudioRecord();

            byte[] audioData = out.toByteArray();

            // If user manually stopped or too short, cancel
            if (!isRecording || audioData.length < 8000) {
                Log.d(TAG, "Recording cancelled or too short");
                resetVoice();
                return;
            }

            runOnUiThread(() -> {
                if (binding.voiceStatus != null) {
                    binding.voiceStatus.setText("⚙ Processing...");
                }
            });

            sendAudioToServer(audioData);

        } catch (SecurityException se) {
            Log.e(TAG, "Microphone permission revoked", se);
            runOnUiThread(() -> showSnackbar("Microphone permission denied"));
            releaseAudioRecord();
            resetVoice();
        } catch (Exception e) {
            Log.e(TAG, "Voice recording failed", e);
            runOnUiThread(() -> showSnackbar("Recording failed"));
            releaseAudioRecord();
            resetVoice();
        }
    }

    private void sendAudioToServer(byte[] pcm) {
        HttpsURLConnection conn = null;
        try {
            // ✅ NEW: Convert PCM to WAV
            byte[] wavData = WavHelper.pcmToWav(pcm, SAMPLE_RATE, 1);

            Log.d(TAG, "📤 Sending WAV: " + wavData.length + " bytes");
            Log.d(TAG, WavHelper.getWavInfo(wavData)); // Debug info

            URL url = new URL(AI_SERVER_URL);
            conn = (HttpsURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            // ✅ CHANGED: Set Content-Type to audio/wav
            conn.setRequestProperty("Content-Type", "audio/wav");
            conn.setRequestProperty("X-ESP-TOKEN", ESP_TOKEN);
            conn.setRequestProperty("X-DEVICE-ID", "android_app");
            conn.setRequestProperty("X-LANGUAGE", "ta");

            // ✅ CHANGED: Send WAV data instead of PCM
            OutputStream os = conn.getOutputStream();
            os.write(wavData);
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            Log.d(TAG, "📥 Server response: " + responseCode);

            if (responseCode == 200) {
                InputStream in = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                StringBuilder response = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                in.close();

                String jsonResponse = response.toString();
                Log.d(TAG, "📄 Response: " + jsonResponse);

                try {
                    JSONObject json = new JSONObject(jsonResponse);

                    String transcript = json.optString("transcript", "");
                    String reply = json.optString("reply", "");
                    boolean audioSent = json.optBoolean("audio_sent", false);

                    Log.d(TAG, "✅ Transcript: " + transcript);
                    Log.d(TAG, "✅ Reply: " + reply);

                    String message = audioSent ? "✓ " + reply + " 🔊" : "✓ " + reply;

                    runOnUiThread(() -> {
                        showSnackbar(message);
                        if (binding.voiceStatus != null) {
                            binding.voiceStatus.setText("✓ " + (transcript.isEmpty() ? "Done" : transcript));
                        }
                    });

                } catch (Exception e) {
                    Log.e(TAG, "JSON parse error", e);
                    runOnUiThread(() -> {
                        showSnackbar("✓ Command sent");
                        if (binding.voiceStatus != null) {
                            binding.voiceStatus.setText("✓ Done");
                        }
                    });
                }

            } else {
                Log.e(TAG, "❌ Server error: " + responseCode);

                InputStream errorStream = conn.getErrorStream();
                if (errorStream != null) {
                    BufferedReader errorReader = new BufferedReader(new InputStreamReader(errorStream));
                    String errorLine = errorReader.readLine();
                    Log.e(TAG, "Error: " + errorLine);
                    errorStream.close();
                }

                runOnUiThread(() -> showSnackbar("Server error: " + responseCode));
            }

        } catch (java.net.SocketTimeoutException e) {
            Log.e(TAG, "⏱️ Server timeout", e);
            runOnUiThread(() -> showSnackbar("Server timeout - try again"));
        } catch (Exception e) {
            Log.e(TAG, "❌ Communication failed", e);
            runOnUiThread(() -> showSnackbar("Error: " + e.getMessage()));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            resetVoice();
        }
    }

    private void resetVoice() {
        runOnUiThread(() -> {
            isRecording = false;

            if (binding.voiceBtn != null) {
                binding.voiceBtn.setBackgroundTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(this, R.color.mic_idle)));
            }

            if (binding.micPulse != null) {
                binding.micPulse.clearAnimation();
                binding.micPulse.setVisibility(View.GONE);
            }

            mainHandler.postDelayed(() -> {
                if (binding.voiceStatus != null) {
                    String currentText = binding.voiceStatus.getText().toString();
                    if (currentText.startsWith("⚙")) {
                        binding.voiceStatus.setText("Ready");
                    }
                }
            }, 2000);
        });
    }

    // =====================================================
    // HELPERS
    // =====================================================

    private void toggleDevice(String d, ProgressBar l) {
        boolean s = d.equals("light") ? !lightOn :
                d.equals("fan1") ? !fan1On :
                        d.equals("fan2") ? !fan2On : !tvOn;

        if (l != null) {
            l.setVisibility(ProgressBar.VISIBLE);
        }
        sendCommand("home/" + d, s ? "1" : "0", l);
    }

    private void allOn() {
        sendCommand("home/light", "1", null);
        sendCommand("home/fan1", "1", null);
        sendCommand("home/fan2", "1", null);
        sendCommand("home/tv", "1", null);
    }

    private void allOff() {
        sendCommand("home/light", "0", null);
        sendCommand("home/fan1", "0", null);
        sendCommand("home/fan2", "0", null);
        sendCommand("home/tv", "0", null);
    }

    private void updateCard(MaterialCardView c, ImageView i, boolean on) {
        if (c == null || i == null) return;

        // FIX #2: Use ContextCompat.getColor()
        c.setCardBackgroundColor(
                ContextCompat.getColor(this, on ? R.color.card_background_on
                        : R.color.card_background_off));
        i.setImageTintList(
                ColorStateList.valueOf(
                        ContextCompat.getColor(this, on ? R.color.icon_on
                                : R.color.icon_off)));
    }

    private void updateConnectionStatus(boolean connected) {
        if (binding.connectionStatus != null) {
            binding.connectionStatus.setText(
                    connected ? "Connected (MQTT Audio)" : "Disconnected");

            int color = ContextCompat.getColor(this, connected
                    ? android.R.color.holo_green_light
                    : android.R.color.holo_red_light);

            binding.connectionStatus.setTextColor(color);

            if (binding.statusIndicator != null) {
                binding.statusIndicator.setBackgroundTintList(
                        ColorStateList.valueOf(color)
                );
            }
        }
    }

    // FIX #10: Use Snackbar instead of Toast spam
    private void showSnackbar(String message) {
        if (binding.getRoot() != null) {
            Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // FIX #5: Clear all callbacks
        if (syncTimeoutRunnable != null) {
            mainHandler.removeCallbacks(syncTimeoutRunnable);
        }
        mainHandler.removeCallbacksAndMessages(null);

        // Stop animations
        if (binding.micPulse != null) {
            binding.micPulse.clearAnimation();
        }

        // Disconnect MQTT
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error disconnecting MQTT", e);
        }

        // Release audio resources
        releaseAudioRecord();

        // Shutdown executor
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }
}