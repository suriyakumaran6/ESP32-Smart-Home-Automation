package com.example.home;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;

public class DashboardActivity extends AppCompatActivity {

    private static final String TAG = "Dashboard";
    private static final int PERMISSION_REQUEST_CODE = 2001;

    private MaterialCardView entranceCard;
    private MaterialCardView hallCard;
    private MaterialCardView bedroomCard;
    private MaterialCardView kitchenCard;

    private View dashboardStatusIndicator;
    private TextView dashboardConnectionStatus;
    private TextView voiceStatusDashboard;
    private TextView lastCommandDashboard;
    private View micPulseDashboard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        // Initialize views safely
        entranceCard = findViewById(R.id.entranceCard);
        hallCard = findViewById(R.id.hallCard);
        bedroomCard = findViewById(R.id.bedroomCard);
        kitchenCard = findViewById(R.id.kitchenCard);

        dashboardStatusIndicator = findViewById(R.id.dashboardStatusIndicator);
        dashboardConnectionStatus = findViewById(R.id.dashboardConnectionStatus);
        voiceStatusDashboard = findViewById(R.id.voiceStatusDashboard);
        lastCommandDashboard = findViewById(R.id.lastCommandDashboard);
        micPulseDashboard = findViewById(R.id.micPulseDashboard);

        // FIX: Safe animation loading
        if (micPulseDashboard != null) {
            try {
                Animation pulseAnim = AnimationUtils.loadAnimation(this, R.anim.mic_pulse);
                micPulseDashboard.startAnimation(pulseAnim);
                micPulseDashboard.setVisibility(View.VISIBLE);
            } catch (Exception e) {
                Log.w(TAG, "mic_pulse animation not found", e);
                // Hide pulse view if animation doesn't exist
                micPulseDashboard.setVisibility(View.GONE);
            }
        }

        // Setup room navigation safely
        if (entranceCard != null) {
            entranceCard.setOnClickListener(v -> openRoom("Entrance"));
        }
        if (hallCard != null) {
            hallCard.setOnClickListener(v -> openRoom("Hall"));
        }
        if (bedroomCard != null) {
            bedroomCard.setOnClickListener(v -> openRoom("Bedroom"));
        }
        if (kitchenCard != null) {
            kitchenCard.setOnClickListener(v -> openRoom("Kitchen"));
        }

        // Check permissions and start voice service
        if (hasRequiredPermissions()) {
            startVoiceService();
        } else {
            requestPermissions();
        }

        updateInitialUIState();
    }

    private void openRoom(String room) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("ROOM_NAME", room);
        startActivity(intent);
    }

    private void updateInitialUIState() {
        if (dashboardConnectionStatus != null) {
            dashboardConnectionStatus.setText("Always Listening...");
        }
        if (voiceStatusDashboard != null) {
            voiceStatusDashboard.setText("🎙 Always Listening");
        }
        if (lastCommandDashboard != null) {
            lastCommandDashboard.setText("Say 'appu' to activate");
        }
    }

    // =====================================================
    // PERMISSIONS
    // =====================================================

    private boolean hasRequiredPermissions() {
        boolean hasMic = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED;

        boolean hasNotification = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotification = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED;
        }

        return hasMic && hasNotification;
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.POST_NOTIFICATIONS
                    },
                    PERMISSION_REQUEST_CODE
            );
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST_CODE
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Toast.makeText(this, "Permissions granted - Starting voice service",
                        Toast.LENGTH_SHORT).show();
                startVoiceService();
            } else {
                Toast.makeText(this, "Microphone permission is required for voice commands",
                        Toast.LENGTH_LONG).show();

                if (voiceStatusDashboard != null) {
                    voiceStatusDashboard.setText("⚠️ Permission Required");
                }
                if (lastCommandDashboard != null) {
                    lastCommandDashboard.setText("Grant microphone permission to use voice commands");
                }

                // Stop pulse animation if permission denied
                if (micPulseDashboard != null) {
                    micPulseDashboard.clearAnimation();
                    micPulseDashboard.setVisibility(View.GONE);
                }
            }
        }
    }

    // =====================================================
    // SERVICE MANAGEMENT
    // =====================================================

    private void startVoiceService() {
        try {
            Intent serviceIntent = new Intent(this, VoiceListenerService.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            Toast.makeText(this, "Voice assistant started", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Voice service started successfully");

        } catch (Exception e) {
            Log.e(TAG, "Failed to start voice service", e);
            Toast.makeText(this, "Failed to start voice service: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // FIX: Stop animations to prevent memory leaks
        if (micPulseDashboard != null) {
            micPulseDashboard.clearAnimation();
        }

        // Keep service running in background
        // User can stop it from notification or app settings
        Log.d(TAG, "Dashboard destroyed, voice service continues");
    }

    @Override
    protected void onResume() {
        super.onResume();

        // FIX: Restart animation when returning to dashboard
        if (micPulseDashboard != null && hasRequiredPermissions()) {
            try {
                Animation pulseAnim = AnimationUtils.loadAnimation(this, R.anim.mic_pulse);
                micPulseDashboard.startAnimation(pulseAnim);
                micPulseDashboard.setVisibility(View.VISIBLE);
            } catch (Exception e) {
                Log.w(TAG, "Could not restart pulse animation", e);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Stop animation when leaving to save battery
        if (micPulseDashboard != null) {
            micPulseDashboard.clearAnimation();
        }
    }
}