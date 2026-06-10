package com.example.home;

import android.util.Log;

import java.util.LinkedList;
import java.util.Queue;

/**
 * WakeWordDetector v3.0
 * Offline heuristic wake-word detector for "appu"
 *
 * Audio format: PCM16, 16kHz, mono
 */
public class WakeWordDetector {

    private static final String TAG = "WakeWordDetector";

    // Audio parameters
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SIZE = 512; // samples (32ms)

    // Energy thresholds (adaptive)
    private static final int MIN_DYNAMIC_ENERGY = 1500;
    private static final int MAX_ENERGY = 50000;

    // Hysteresis
    private static final float ENERGY_RISE_FACTOR = 2.0f;
    private static final float ENERGY_FALL_FACTOR = 1.2f;

    // Wake word parameters
    private static final int TARGET_SYLLABLES = 2; // "ap-pu"
    private static final long MAX_SYLLABLE_GAP_MS = 400;
    private static final long MIN_SYLLABLE_GAP_MS = 100;

    // ZCR limits (speech-like)
    private static final int MIN_ZCR = 5;
    private static final int MAX_ZCR = 80;

    // Rolling syllable buffer
    private final Queue<SyllableInfo> recentSyllables = new LinkedList<>();

    // State
    private float noiseFloorEnergy = 2000;
    private boolean inHighEnergy = false;
    private long audioTimeMs = 0;
    private short lastSample = 0;

    private static class SyllableInfo {
        final long timestampMs;
        final int energy;
        final int zcr;

        SyllableInfo(long timestampMs, int energy, int zcr) {
            this.timestampMs = timestampMs;
            this.energy = energy;
            this.zcr = zcr;
        }
    }

    /**
     * Process raw PCM audio
     */
    public boolean processAudio(byte[] audioData, int length) {
        int frameBytes = FRAME_SIZE * 2;

        for (int offset = 0; offset <= length - frameBytes; offset += frameBytes) {
            if (processFrame(audioData, offset)) {
                return true;
            }
            audioTimeMs += (FRAME_SIZE * 1000L) / SAMPLE_RATE;
        }
        return false;
    }

    private boolean processFrame(byte[] audioData, int offset) {
        long energySum = 0;
        int zeroCrossings = 0;

        for (int i = 0; i < FRAME_SIZE * 2; i += 2) {
            int idx = offset + i;
            if (idx + 1 >= audioData.length) break;

            short sample = (short) ((audioData[idx + 1] << 8) | (audioData[idx] & 0xFF));

            // True energy (square)
            energySum += (long) sample * sample;

            // Continuous ZCR
            if ((lastSample > 0 && sample < 0) || (lastSample < 0 && sample > 0)) {
                zeroCrossings++;
            }
            lastSample = sample;
        }

        int rmsEnergy = (int) Math.sqrt(energySum / FRAME_SIZE);

        // Update adaptive noise floor
        if (!inHighEnergy) {
            noiseFloorEnergy = 0.98f * noiseFloorEnergy + 0.02f * rmsEnergy;
            noiseFloorEnergy = Math.max(noiseFloorEnergy, MIN_DYNAMIC_ENERGY);
        }

        boolean rising =
                rmsEnergy > noiseFloorEnergy * ENERGY_RISE_FACTOR &&
                        rmsEnergy < MAX_ENERGY;

        boolean falling =
                rmsEnergy < noiseFloorEnergy * ENERGY_FALL_FACTOR;

        if (rising && !inHighEnergy) {
            inHighEnergy = true;

            if (zeroCrossings >= MIN_ZCR && zeroCrossings <= MAX_ZCR) {
                registerSyllable(rmsEnergy, zeroCrossings);
                if (matchesWakeWordPattern()) {
                    Log.d(TAG, "Wake word detected!");
                    reset();
                    return true;
                }
            }
        } else if (falling) {
            inHighEnergy = false;
        }

        return false;
    }

    private void registerSyllable(int energy, int zcr) {
        recentSyllables.add(new SyllableInfo(audioTimeMs, energy, zcr));

        // Keep last 1 second only
        while (!recentSyllables.isEmpty() &&
                audioTimeMs - recentSyllables.peek().timestampMs > 1000) {
            recentSyllables.poll();
        }
    }

    private boolean matchesWakeWordPattern() {
        if (recentSyllables.size() < TARGET_SYLLABLES) {
            return false;
        }

        SyllableInfo[] s = recentSyllables.toArray(new SyllableInfo[0]);
        int n = s.length;

        SyllableInfo first = s[n - 2];
        SyllableInfo second = s[n - 1];

        long gap = second.timestampMs - first.timestampMs;
        if (gap < MIN_SYLLABLE_GAP_MS || gap > MAX_SYLLABLE_GAP_MS) {
            return false;
        }

        int minEnergy = Math.min(first.energy, second.energy);
        if (minEnergy == 0) return false;

        int energyRatio =
                Math.max(first.energy, second.energy) / minEnergy;

        if (energyRatio > 3) {
            return false;
        }

        Log.d(TAG, String.format(
                "Pattern OK: gap=%dms e1=%d e2=%d z1=%d z2=%d",
                gap, first.energy, second.energy, first.zcr, second.zcr
        ));

        return true;
    }

    /**
     * Reset detector state
     */
    public void reset() {
        recentSyllables.clear();
        noiseFloorEnergy = 2000;
        inHighEnergy = false;
        lastSample = 0;
        audioTimeMs = 0;
    }
}
