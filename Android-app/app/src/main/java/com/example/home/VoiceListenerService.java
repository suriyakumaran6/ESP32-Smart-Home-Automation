package com.example.home;

import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.*;
import java.net.URL;
import java.util.concurrent.*;

import javax.net.ssl.HttpsURLConnection;

public class VoiceListenerService extends Service {

    private static final String TAG = "VoiceListener";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "voice_listener_channel";

    // Audio config
    private static final int SAMPLE_RATE = 16000;
    private static final int RECORD_DURATION_MS = 3000;
    private static final int WAKE_WORD_BUFFER_MS = 2000;

    // Server config - USE BuildConfig in production
    private static final String MQTT_BROKER = "ssl://4348e232bb5545858370951f4fe2d907.s1.eu.hivemq.cloud:8883";
    private String MQTT_USER = BuildConfig.MQTT_USER;
    private String MQTT_PASS = BuildConfig.MQTT_PASS;
    private String ESP_TOKEN = BuildConfig.ESP_TOKEN;
    private static final String AI_SERVER_URL = "https://ai-server-7qry.onrender.com/voice";

    // Components
    private AudioRecord audioRecord;
    private MqttClient mqttClient;
    private ExecutorService executor;
    private Handler mainHandler;
    private WakeWordDetector wakeWordDetector;

    // State
    private volatile boolean isListening = false;
    private volatile boolean isProcessing = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");

        executor = Executors.newCachedThreadPool();
        mainHandler = new Handler(Looper.getMainLooper());
        wakeWordDetector = new WakeWordDetector();

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."));

        executor.execute(this::setupMQTT);
        executor.execute(this::startWakeWordDetection);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ================== MQTT SETUP ==================

    private void setupMQTT() {
        try {
            String clientId = "VoiceService_" + System.currentTimeMillis();

            mqttClient = new MqttClient(MQTT_BROKER, clientId, new MemoryPersistence());

            MqttConnectOptions opt = new MqttConnectOptions();
            opt.setUserName(MQTT_USER);
            opt.setPassword(MQTT_PASS.toCharArray());
            opt.setAutomaticReconnect(true);
            opt.setCleanSession(true);
            opt.setConnectionTimeout(10);
            opt.setKeepAliveInterval(30);

            mqttClient.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    Log.w(TAG, "MQTT connection lost", cause);
                    updateNotification("Reconnecting...");
                }

                @Override
                public void messageArrived(String topic, MqttMessage msg) {
                    if (topic.startsWith("home/audio/")) {
                        playAudio(msg.getPayload());
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            mqttClient.connect(opt);
            mqttClient.subscribe("home/audio/#", 1);

            Log.d(TAG, "MQTT connected");
            updateNotification("Listening for 'appu'...");

        } catch (Exception e) {
            Log.e(TAG, "MQTT setup failed", e);
            updateNotification("Connection error");

            // ✅ FIX ERROR 4: Removed isListening check - always retry
            mainHandler.postDelayed(() -> {
                executor.execute(this::setupMQTT);
            }, 5000);
        }
    }

    // ================== WAKE WORD DETECTION ==================

    private void startWakeWordDetection() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "No microphone permission - stopping service");
            updateNotification("Permission denied");
            stopSelf();
            return;
        }

        try {
            // ✅ FIX ERROR 1: Changed AudioTrack to AudioRecord
            int minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            int bufferSize = Math.max(minBuffer * 2, 8192);

            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size");
                stopSelf();
                return;
            }

            // Use larger buffer for continuous recording
            int actualBufferSize = Math.max(bufferSize * 4, 8192);

            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    actualBufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized");
                updateNotification("Microphone initialization failed");
                stopSelf();
                return;
            }

            audioRecord.startRecording();
            isListening = true;
            updateNotification("Listening for 'appu'...");

            byte[] buffer = new byte[bufferSize];
            int rollingBufferSize = (SAMPLE_RATE * WAKE_WORD_BUFFER_MS) / 1000 * 2;
            ByteArrayOutputStream rollingBuffer = new ByteArrayOutputStream(rollingBufferSize);

            while (isListening) {
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                if (bytesRead <= 0) {
                    Log.w(TAG, "Audio read error: " + bytesRead);
                    continue;
                }

                // Maintain rolling buffer
                rollingBuffer.write(buffer, 0, bytesRead);
                byte[] allData = rollingBuffer.toByteArray();
                if (allData.length > rollingBufferSize) {
                    rollingBuffer.reset();
                    rollingBuffer.write(allData, allData.length - rollingBufferSize, rollingBufferSize);
                }

                if (!isProcessing && wakeWordDetector.processAudio(buffer, bytesRead)) {
                    Log.d(TAG, "Wake word 'appu' detected!");
                    updateNotification("Processing command...");

                    byte[] preWakeAudio = rollingBuffer.toByteArray().clone();
                    executor.execute(() -> recordFullCommand(preWakeAudio));

                    // Reset detector for next wake word
                    wakeWordDetector.reset();

                    // Brief pause to avoid double detection
                    Thread.sleep(500);
                }
            }

            // ✅ FIX ERROR 2: REMOVED invalid synchronized block

        } catch (InterruptedException ie) {
            Log.d(TAG, "Wake word detection interrupted");
        } catch (Exception e) {
            Log.e(TAG, "Wake word detection failed", e);
            updateNotification("Microphone error");
        } finally {
            // ✅ FIX ISSUE 6: Added finally block to release AudioRecord
            if (audioRecord != null) {
                try {
                    if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                        audioRecord.stop();
                    }
                    audioRecord.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing AudioRecord in finally", e);
                }
                audioRecord = null;
            }
        }
    }


    // ================== RECORD COMMAND ==================

    private void recordFullCommand(byte[] preAudio) {
        if (isProcessing) {
            Log.w(TAG, "Already processing a command");
            return;
        }

        isProcessing = true;

        try {
            ByteArrayOutputStream commandAudio = new ByteArrayOutputStream();

            // Include pre-wake audio
            if (preAudio != null && preAudio.length > 0) {
                commandAudio.write(preAudio);
                Log.d(TAG, "Added " + preAudio.length + " bytes of pre-wake audio");
            }

            byte[] buffer = new byte[SAMPLE_RATE / 10]; // 100ms chunks
            long startTime = System.currentTimeMillis();
            long duration = RECORD_DURATION_MS - WAKE_WORD_BUFFER_MS;

            // ✅ FIX ERROR 3: Create final local reference for lambda-safe access
            final AudioRecord recorder = audioRecord;
            if (recorder == null) {
                Log.e(TAG, "AudioRecord is null, cannot record command");
                return;
            }

            while (System.currentTimeMillis() - startTime < duration && isListening) {
                synchronized (recorder) {
                    int read = recorder.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        commandAudio.write(buffer, 0, read);
                    } else {
                        Log.w(TAG, "Audio read error during recording: " + read);
                    }
                }
            }

            byte[] audioData = commandAudio.toByteArray();
            Log.d(TAG, "Recorded " + audioData.length + " bytes total");

            if (audioData.length >= 8000) { // At least 0.25s of audio
                sendToServer(audioData);
            } else {
                Log.w(TAG, "Audio too short: " + audioData.length + " bytes");
                updateNotification("Audio too short - try again");
            }

        } catch (Exception e) {
            Log.e(TAG, "Recording failed", e);
            updateNotification("Recording error");
        } finally {
            isProcessing = false;
            mainHandler.postDelayed(() ->
                    updateNotification("Listening for 'appu'..."), 2000);
        }
    }

    private void sendToServer(byte[] audioData) {
        executor.execute(() -> {
            HttpsURLConnection conn = null;
            try {
                // Convert PCM to WAV
                byte[] wavData = WavHelper.pcmToWav(audioData, SAMPLE_RATE, 1);

                Log.d(TAG, "📤 Sending WAV: " + wavData.length + " bytes to AI server");

                URL url = new URL(AI_SERVER_URL);
                conn = (HttpsURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);

                conn.setRequestProperty("Content-Type", "audio/wav");
                conn.setRequestProperty("X-ESP-TOKEN", ESP_TOKEN);
                conn.setRequestProperty("X-DEVICE-ID", "android_service");
                conn.setRequestProperty("X-LANGUAGE", "ta");

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

                    Log.d(TAG, "✅ Response: " + response.toString());
                    updateNotification("Command processed ✓");

                } else {
                    InputStream errorStream = conn.getErrorStream();
                    if (errorStream != null) {
                        BufferedReader errorReader = new BufferedReader(
                                new InputStreamReader(errorStream));
                        StringBuilder errorResponse = new StringBuilder();
                        String errorLine;
                        while ((errorLine = errorReader.readLine()) != null) {
                            errorResponse.append(errorLine);
                        }
                        errorStream.close();

                        Log.e(TAG, "❌ Server error " + responseCode + ": " + errorResponse.toString());
                        updateNotification("Server error " + responseCode);
                    } else {
                        Log.e(TAG, "❌ Server error: " + responseCode);
                        updateNotification("Server error " + responseCode);
                    }
                }

            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "⏱️ Server timeout", e);
                updateNotification("Server timeout");
            } catch (Exception e) {
                Log.e(TAG, "❌ Server communication failed", e);
                updateNotification("Network error");
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    // ================== AUDIO PLAYBACK ==================

    private void playAudio(byte[] audioData) {
        if (audioData == null || audioData.length == 0) {
            Log.w(TAG, "No audio data to play");
            return;
        }

        // Process audio data before passing to executor (make it final)
        final byte[] processedAudioData;
        if (WavHelper.isWavFormat(audioData)) {
            processedAudioData = WavHelper.stripWavHeader(audioData);
        } else {
            processedAudioData = audioData;
        }

        executor.execute(() -> {
            AudioTrack track = null;
            try {
                // ✅ FIX ISSUE 5: Increased buffer size for playback
                int minBuffer = AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                );
                int bufferSize = Math.max(minBuffer * 2, 8192);

                track = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize,
                        AudioTrack.MODE_STREAM
                );

                track.play();

                int offset = 0;
                while (offset < processedAudioData.length) {
                    int chunk = Math.min(4096, processedAudioData.length - offset);
                    track.write(processedAudioData, offset, chunk);
                    offset += chunk;
                }

                if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop();
                }
                track.release();

                Log.d(TAG, "Audio playback completed");

            } catch (Exception e) {
                Log.e(TAG, "Audio playback failed", e);
                if (track != null) {
                    try { track.release(); } catch (Exception ignored) {}
                }
            }
        });
    }

    // ================== NOTIFICATION ==================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Voice Assistant",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Always listening for voice commands");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification(String status) {
        Intent intent = new Intent(this, DashboardActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Voice Assistant")
                .setContentText(status)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String status) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification(status));
        }
    }

    // ================== CLEANUP ==================

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroying...");

        isListening = false;
        isProcessing = false;

        mainHandler.removeCallbacksAndMessages(null);

        if (audioRecord != null) {
            try {
                if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing AudioRecord", e);
            } finally {
                audioRecord = null;
            }
        }

        if (mqttClient != null) {
            try {
                if (mqttClient.isConnected()) {
                    mqttClient.disconnect();
                }
                mqttClient.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing MQTT", e);
            }
        }

        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }

        Log.d(TAG, "Service destroyed");
    }
}