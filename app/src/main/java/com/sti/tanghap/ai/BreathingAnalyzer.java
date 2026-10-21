package com.sti.tanghap.ai;

import com.sti.tanghap.ai.processor.SignalProcessor;

/**
 * AI-assisted breathing pattern analysis based on pressure sensor fluctuations.
 */
public class BreathingAnalyzer {

    /** Minimum |kPa| that counts as an inhalation effort (sign-independent). */
    public static final float INHALATION_THRESHOLD_KPA = 1.5f;
    /** Less data than this gives an unreliable rate, so no BPM is reported yet. */
    public static final float MIN_WINDOW_SEC = 10f;
    /** Two breaths closer than this are treated as one (caps detection at 60 BPM). */
    private static final float MIN_BREATH_SPACING_SEC = 1.0f;

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
     * @param pressureData Pressure values in kPa from the ESP32, in arrival order
     * @param durationSec  Real time in seconds covered by {@code pressureData}
     * @return BreathingResult containing BPM and insights
     */
    public BreathingResult analyze(float[] pressureData, float durationSec) {
        if (pressureData == null || pressureData.length < 3 || durationSec <= 0) {
            return new BreathingResult(0, "Awaiting Inhalation...", "No pressure data available.", "Normal");
        }
        if (durationSec < MIN_WINDOW_SEC) {
            return new BreathingResult(0, "Measuring breathing rate...",
                    "Keep breathing through the mouthpiece for at least 10 seconds.", "Normal");
        }

        // 1. Preprocessing: filter noise, then use magnitude so the result does not depend on
        //    whether the sensor reports inhalation as a positive or negative pressure.
        float[] filtered = SignalProcessor.applyLowPassFilter(pressureData, 0.15f);
        float[] magnitude = new float[filtered.length];
        for (int i = 0; i < filtered.length; i++) magnitude[i] = Math.abs(filtered[i]);

        // 2. Inhalation peak detection with a time-based minimum spacing
        float sampleRateHz = pressureData.length / durationSec;
        int minDistance = Math.max(1, Math.round(sampleRateHz * MIN_BREATH_SPACING_SEC));
        int peaks = SignalProcessor.countPeaks(magnitude, INHALATION_THRESHOLD_KPA, minDistance);

        // 3. Compute BPM over the real elapsed time
        int bpm = Math.round(peaks * 60f / durationSec);

        return classify(bpm);
    }

    /** Pattern classification for a breathing rate (also used for the session average). */
    public static BreathingResult classify(int bpm) {
        String pattern;
        String insight;
        String severity;

        if (bpm <= 0) {
            return new BreathingResult(0, "Awaiting Inhalation...", "Inhale through the mouthpiece to measure breathing rate.", "Normal");
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
