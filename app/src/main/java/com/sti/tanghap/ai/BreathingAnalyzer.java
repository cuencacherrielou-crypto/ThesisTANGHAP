package com.sti.tanghap.ai;

import com.sti.tanghap.ai.processor.SignalProcessor;
import com.sti.tanghap.domain.RespiratoryNorms;

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
        return analyze(pressureData, durationSec, -1);
    }

    /** Same as {@link #analyze(float[], float)}, judging the rate against the user's age. */
    public BreathingResult analyze(float[] pressureData, float durationSec, int ageYears) {
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

        return classify(bpm, ageYears);
    }

    /** Pattern classification for a breathing rate, using adult limits (12-20 BPM). */
    public static BreathingResult classify(int bpm) {
        return classify(bpm, -1);
    }

    /**
     * Pattern classification for a breathing rate (also used for the session average).
     *
     * @param ageYears the user's age; negative when unknown (adult limits are used)
     */
    public static BreathingResult classify(int bpm, int ageYears) {
        String pattern;
        String insight;
        String severity;
        String range = RespiratoryNorms.rangeText(ageYears);

        if (bpm <= 0) {
            return new BreathingResult(0, "Awaiting Inhalation...", "Inhale through the mouthpiece to measure breathing rate.", "Normal");
        } else if (RespiratoryNorms.isAboveNormal(bpm, ageYears)) {
            pattern = "Rapid Breathing (Tachypnea-like)";
            insight = "Breathing is faster than the normal " + range + " range. This can happen with exertion, "
                    + "and there is a possibility of respiratory distress.";
            severity = "High";
        } else if (RespiratoryNorms.isBelowNormal(bpm, ageYears)) {
            pattern = "Slow Breathing (Bradypnea-like)";
            insight = "Breathing is slower than the normal " + range + " range. This can happen with deep relaxation, "
                    + "and there is a possibility of suppressed breathing.";
            severity = "Low";
        } else {
            pattern = "Normal Breathing";
            insight = "Respiratory rhythm is within the normal range (" + range + ").";
            severity = "Normal";
        }

        return new BreathingResult(bpm, pattern, insight, severity);
    }
}
