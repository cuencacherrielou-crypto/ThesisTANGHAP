package com.sti.tanghap.ai.processor;

import org.jtransforms.fft.FloatFFT_1D;

/**
 * Advanced Signal Processing for TANGHAP Sensors
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
     * Performs FFT on audio or pressure waveforms to extract frequency domain features.
     */
    public static float[] computeFFT(float[] signal) {
        if (signal == null || signal.length == 0) return new float[0];
        int n = signal.length;
        FloatFFT_1D fft = new FloatFFT_1D(n);
        
        // Using complexForward for easier magnitude calculation and predictability
        float[] fftData = new float[n * 2];
        for (int i = 0; i < n; i++) {
            fftData[2 * i] = signal[i];
            fftData[2 * i + 1] = 0;
        }
        
        fft.complexForward(fftData);
        
        // Extract magnitude spectrum (first half)
        float[] magnitudes = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            float re = fftData[2 * i];
            float im = fftData[2 * i + 1];
            magnitudes[i] = (float) Math.sqrt(re * re + im * im);
        }
        return magnitudes;
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
