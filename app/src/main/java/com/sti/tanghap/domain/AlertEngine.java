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
            int todayCoughCount,
            int todayActuationCount
    ) {
        List<Alert> out = new ArrayList<>();

        // Age-based Breathing Pattern Analysis (unknown age = adult range). The rate is estimated
        // from breathing sounds and only its pattern is shown, never the number.
        if (todayBpm > 0) {
            int[] range = RespiratoryNorms.normalRange(userAge);
            int minBpm = range[0];
            int maxBpm = range[1];

            if (todayBpm < minBpm || todayBpm > maxBpm) {
                String status = todayBpm < minBpm ? "slower" : "faster";
                out.add(new Alert(
                        "Breathing " + status + " than normal",
                        "Your breathing in your last session sounded " + status + " than is typical for your age. This is a trend indicator only.",
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

        return out;
    }
}
