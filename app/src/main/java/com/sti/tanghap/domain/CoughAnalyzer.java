package com.sti.tanghap.domain;

import java.util.List;

public class CoughAnalyzer {
    public enum CoughType {
        DRY("Dry Cough", "Sharp and short cough, typically non-productive."),
        WET("Wet/Productive Cough", "Heavier cough with mucus-like vibration detected."),
        FREQUENT("Frequent Cough", "Multiple cough bursts detected in a short period."),
        SEVERE("Severe/Worsening Cough", "Loud, long, and frequent coughing episodes."),
        NORMAL("No Significant Cough", "Normal respiratory sounds.");

        public final String label;
        public final String recommendation;

        CoughType(String label, String recommendation) {
            this.label = label;
            this.recommendation = recommendation;
        }
    }

    public static class CoughFeatures {
        public int intensity;
        public long durationMillis;
        public boolean hasMucusVibration;
        public long timestamp;

        public CoughFeatures(int intensity, long durationMillis, boolean hasMucusVibration, long timestamp) {
            this.intensity = intensity;
            this.durationMillis = durationMillis;
            this.hasMucusVibration = hasMucusVibration;
            this.timestamp = timestamp;
        }
    }

    /**
     * Threshold-based cough classification
     */
    public CoughType analyze(List<CoughFeatures> recentCoughs, float recentPeakPressure) {
        if (recentCoughs == null || recentCoughs.isEmpty()) return CoughType.NORMAL;

        int count = recentCoughs.size();
        float avgIntensity = 0;
        float avgDuration = 0;
        int mucusCount = 0;

        for (CoughFeatures c : recentCoughs) {
            avgIntensity += c.intensity;
            avgDuration += c.durationMillis;
            if (c.hasMucusVibration) mucusCount++;
        }
        avgIntensity /= count;
        avgDuration /= count;

        // 1. Severe/Worsening: High intensity, long duration, and frequent
        if (avgIntensity > 200 && avgDuration > 1000 && count >= 3) {
            return CoughType.SEVERE;
        }

        // 2. Frequent: Repetitive bursts
        if (count >= 4) {
            return CoughType.FREQUENT;
        }

        // 3. Wet: Heavier sound with vibration or mucus
        if (mucusCount > 0 || (avgIntensity > 150 && avgDuration > 800)) {
            return CoughType.WET;
        }

        // 4. Dry: Sharp, short
        if (avgIntensity > 100 && avgDuration < 500) {
            return CoughType.DRY;
        }

        return CoughType.DRY; // Default to Dry if it's a basic cough
    }
    
    /**
     * Advanced check combining with pressure sensor data
     */
    public String getClinicalFinding(CoughType type, boolean breathingInterrupted, boolean rapidInhale) {
        StringBuilder sb = new StringBuilder(type.label);
        if (breathingInterrupted) {
            sb.append(" with breathing interruption");
        }
        if (rapidInhale) {
            sb.append(" followed by rapid inhalation (gasping)");
        }
        return sb.toString();
    }
}
