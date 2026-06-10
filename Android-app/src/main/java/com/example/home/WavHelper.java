package com.example.home;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * WavHelper - Convert PCM audio to WAV format
 *
 * Usage:
 *   byte[] pcmData = recordAudio();
 *   byte[] wavData = WavHelper.pcmToWav(pcmData, 16000, 1);
 *   sendToServer(wavData);
 */
public class WavHelper {

    /**
     * Convert raw PCM data to WAV format
     *
     * @param pcmData Raw PCM audio bytes (16-bit little-endian)
     * @param sampleRate Sample rate in Hz (e.g., 16000)
     * @param channels Number of channels (1 = mono, 2 = stereo)
     * @return WAV file bytes (header + PCM data)
     */
    public static byte[] pcmToWav(byte[] pcmData, int sampleRate, int channels) {
        if (pcmData == null || pcmData.length == 0) {
            throw new IllegalArgumentException("PCM data cannot be null or empty");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            // Calculate sizes
            int pcmDataSize = pcmData.length;
            int totalDataSize = pcmDataSize + 36; // 36 = size of header minus 8 bytes
            int byteRate = sampleRate * channels * 2; // 2 bytes per sample (16-bit)

            // Write WAV header (44 bytes total)

            // RIFF chunk descriptor (12 bytes)
            out.write("RIFF".getBytes());                    // ChunkID (4 bytes)
            out.write(intToBytes(totalDataSize));            // ChunkSize (4 bytes)
            out.write("WAVE".getBytes());                    // Format (4 bytes)

            // fmt sub-chunk (24 bytes)
            out.write("fmt ".getBytes());                    // Subchunk1ID (4 bytes)
            out.write(intToBytes(16));                       // Subchunk1Size (16 for PCM) (4 bytes)
            out.write(shortToBytes((short) 1));              // AudioFormat (1 = PCM) (2 bytes)
            out.write(shortToBytes((short) channels));       // NumChannels (2 bytes)
            out.write(intToBytes(sampleRate));               // SampleRate (4 bytes)
            out.write(intToBytes(byteRate));                 // ByteRate (4 bytes)
            out.write(shortToBytes((short) (channels * 2))); // BlockAlign (2 bytes)
            out.write(shortToBytes((short) 16));             // BitsPerSample (2 bytes)

            // data sub-chunk (8 bytes + PCM data)
            out.write("data".getBytes());                    // Subchunk2ID (4 bytes)
            out.write(intToBytes(pcmDataSize));              // Subchunk2Size (4 bytes)

            // Write PCM data
            out.write(pcmData);

            return out.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("Failed to create WAV file", e);
        }
    }

    /**
     * Strip WAV header and return raw PCM data
     */
    public static byte[] stripWavHeader(byte[] wav) {
        if (!isWavFormat(wav) || wav.length <= 44) {
            return new byte[0];
        }

        byte[] pcm = new byte[wav.length - 44];
        System.arraycopy(wav, 44, pcm, 0, pcm.length);
        return pcm;
    }

    /**
     * Convert int to 4 bytes (little-endian)
     */
    private static byte[] intToBytes(int value) {
        return new byte[] {
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)
        };
    }

    /**
     * Convert short to 2 bytes (little-endian)
     */
    private static byte[] shortToBytes(short value) {
        return new byte[] {
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF)
        };
    }

    /**
     * Check if data is already WAV format
     */
    public static boolean isWavFormat(byte[] data) {
        if (data == null || data.length < 12) {
            return false;
        }

        // Check for "RIFF" and "WAVE" headers
        return data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F' &&
                data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E';
    }

    /**
     * Get WAV file info (for debugging)
     */
    public static String getWavInfo(byte[] wavData) {
        if (!isWavFormat(wavData)) {
            return "Not a valid WAV file";
        }

        try {
            // Read header fields
            int fileSize = bytesToInt(wavData, 4) + 8;
            int audioFormat = bytesToShort(wavData, 20);
            int channels = bytesToShort(wavData, 22);
            int sampleRate = bytesToInt(wavData, 24);
            int byteRate = bytesToInt(wavData, 28);
            int bitsPerSample = bytesToShort(wavData, 34);
            int dataSize = bytesToInt(wavData, 40);

            double duration = (double) dataSize / byteRate;

            return String.format(
                    "WAV Info:\n" +
                            "  File Size: %d bytes\n" +
                            "  Format: %s\n" +
                            "  Channels: %d\n" +
                            "  Sample Rate: %d Hz\n" +
                            "  Bits/Sample: %d\n" +
                            "  Duration: %.2f seconds\n" +
                            "  Data Size: %d bytes",
                    fileSize,
                    (audioFormat == 1 ? "PCM" : "Unknown"),
                    channels,
                    sampleRate,
                    bitsPerSample,
                    duration,
                    dataSize
            );
        } catch (Exception e) {
            return "Error reading WAV info: " + e.getMessage();
        }
    }

    private static int bytesToInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) |
                ((data[offset + 1] & 0xFF) << 8) |
                ((data[offset + 2] & 0xFF) << 16) |
                ((data[offset + 3] & 0xFF) << 24);
    }

    private static short bytesToShort(byte[] data, int offset) {
        return (short) ((data[offset] & 0xFF) |
                ((data[offset + 1] & 0xFF) << 8));
    }



}
