package com.sti.tanghap.ai;

import com.sti.tanghap.ai.processor.SignalProcessor;
import java.util.List;

/**
 * AI-assisted breathing pattern analysis based on pressure sensor fluctuations.
 */
public class BreathingAnalyzer {

    public static class BreathingResult {
        public int bpm;
        public String pattern;
        public String insight;
        public String severity; // Normal, Low, High

        public BreathingResult(int bpm, String pattern, String insight, String severity) {
            this.bpm = bpm;
            this.pattern = pattern;
            this.insight = insight;
            this.severity = severity;
        }
    }

    /**
     * Analyzes pressure sensor data to compute breathing rate and pattern.
     *
     * @param pressureData Processed pressure values in kPa from ESP32
     * @param durationSec Duration of the sample in seconds
     * @return BreathingResult containing BPM and insights
     */
    public BreathingResult analyze(float[] pressureData, int durationSec) {
        if (pressureData == null || pressureData.length == 0 || durationSec <= 0) {
            return new BreathingResult(0, "Awaiting Inhalation...", "No pressure data available.", "Normal");
        }

        // 1. Preprocessing: Filter noise
        float[] filteredData = SignalProcessor.applyLowPassFilter(pressureData, 0.15f);

        // 2. Inhalation Peak Detection
        // Dahil negative pressure (drop) ang Inhalation sa kPa, invert o mag-detect sa kPa threshold (e.g., -1.5 kPa)
        // O kung absolute effort values: gumamit ng positive threshold kung pinalitan sa absolute value
        int peaks = 0;
        float inhalationThreshold = 1.5f; // Requires at least 1.5 kPa effort drop

        for (int i = 1; i < filteredData.length - 1; i++) {
            float absVal = Math.abs(filteredData[i]);
            if (absVal > inhalationThreshold && absVal > Math.abs(filteredData[i - 1]) && absVal > Math.abs(filteredData[i + 1])) {
                peaks++;
                i += 10; // Min distance between peaks (debounce)
            }
        }

        // 3. Compute BPM
        int bpm = Math.round((peaks / (float) durationSec) * 60);

        // 4. Pattern Classification
        String pattern;
        String insight;
        String severity;

        if (bpm == 0) {
            pattern = "Awaiting Inhalation...";
            insight = "Inhale through the mouthpiece to measure breathing rate.";
            severity = "Normal";
        } else if (bpm > 20) {
            pattern = "Rapid Breathing (Tachypnea-like)";
            insight = "Preliminary assessment suggests rapid respiratory rhythm. Associated with possible respiratory distress or exertion.";
            severity = "High";
        } else if (bpm < 12) {
            pattern = "Slow Breathing (Bradypnea-like)";
            insight = "Possible slow respiratory rate detected. May be associated with deep relaxation or potential respiratory suppression.";
            severity = "Low";
        } else {
            pattern = "Normal Breathing";
            insight = "Respiratory rhythm is within the normal range (12-20 bpm).";
            severity = "Normal";
        }

        return new BreathingResult(bpm, pattern, insight, severity);
    }
}