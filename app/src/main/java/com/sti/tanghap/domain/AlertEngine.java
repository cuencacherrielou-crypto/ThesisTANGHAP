package com.sti.tanghap.domain;

import java.util.ArrayList;
import java.util.List;

public class AlertEngine {
    public static class Alert {
        public String title;
        public String message;
        public String severity; // INFO/WARN

        public Alert(String t, String m, String s) {
            title = t;
            message = m;
            severity = s;
        }
    }

    public List<Alert> evaluate(
            BaselineCalculator.Baseline baseline,
            int userAge,
            int todayBpm,
            float todayPeakPressure,
            float todayInhaleDurationMillis,
            int todayCoughCount,
            int todayActuationCount
    ) {
        List<Alert> out = new ArrayList<>();

        // Age-based Breathing Pattern Analysis (unknown age = adult range)
        if (todayBpm > 0) {
            int[] range = RespiratoryNorms.normalRange(userAge);
            int minBpm = range[0];
            int maxBpm = range[1];

            if (todayBpm < minBpm || todayBpm > maxBpm) {
                String status = todayBpm < minBpm ? "lower" : "higher";
                out.add(new Alert(
                        "Breathing rate " + status + " than normal",
                        "Your average breathing rate of " + todayBpm + " BPM is " + status + " than the typical range for your age (" + minBpm + "-" + maxBpm + " BPM). This is a trend indicator only.",
                        "WARN"
                ));
            }
        }

        // Non-diagnostic rules (simple + defensible)
        if (baseline.avgCoughPerSession > 0 && todayCoughCount > baseline.avgCoughPerSession * 1.5f) {
            out.add(new Alert(
                    "Cough frequency higher than usual",
                    "Cough events appear higher than your recent baseline. This is a trend indicator only. If this persists, consider reviewing triggers and consult a clinician.",
                    "WARN"
            ));
        }

        if (baseline.avgActuationPerSession > 0 && todayActuationCount > baseline.avgActuationPerSession + 2) {
            out.add(new Alert(
                    "Frequent inhaler actuation",
                    "Actuation count is above your recent baseline. This is not a diagnosis. If frequent use continues, consider discussing with a healthcare provider.",
                    "WARN"
            ));
        }

        if (baseline.avgInhaleDurationMillis > 0 && todayInhaleDurationMillis > 0
                && todayInhaleDurationMillis < baseline.avgInhaleDurationMillis * 0.7f) {
            out.add(new Alert(
                    "Shorter inhalation duration",
                    "Inhalation duration appears lower than usual. This may indicate technique variation. This is a trend indicator only.",
                    "INFO"
            ));
        }

        // Optional pressure deviation info (0 = no pressure recorded, not a weak inhalation)
        if (baseline.avgPeakPressure > 0 && todayPeakPressure > 0
                && todayPeakPressure < baseline.avgPeakPressure * 0.7f) {
            out.add(new Alert(
                    "Lower peak pressure than baseline",
                    "Peak inhalation pressure is below your baseline. Consider checking inhaler technique. Trend indicator only.",
                    "INFO"
            ));
        }

        return out;
    }
}
