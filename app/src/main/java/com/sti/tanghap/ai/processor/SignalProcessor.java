package com.sti.tanghap.ai.processor;

/**
 * Signal processing for the pressure sensor: smoothing and breath peak counting.
 */
public class SignalProcessor {

    /**
     * Applies a Low-Pass Filter to remove high-frequency noise from pressure sensor data.
     * Useful before peak detection for breathing rate computation.
     */
    public static float[] applyLowPassFilter(float[] input, float alpha) {
        if (input == null || input.length == 0) return input;
        float[] output = new float[input.length];
        output[0] = input[0];
        for (int i = 1; i < input.length; i++) {
            output[i] = output[i - 1] + alpha * (input[i] - output[i - 1]);
        }
        return output;
    }

    /**
     * Detects peaks in the pressure waveform to count breathing cycles.
     */
    public static int countPeaks(float[] data, float threshold, int minDistance) {
        if (data == null || data.length < 3) return 0;
        int count = 0;
        int lastPeakIndex = -minDistance;
        
        for (int i = 1; i < data.length - 1; i++) {
            if (data[i] > threshold && data[i] > data[i - 1] && data[i] > data[i + 1]) {
                if (i - lastPeakIndex >= minDistance) {
                    count++;
                    lastPeakIndex = i;
                }
            }
        }
        return count;
    }
}
