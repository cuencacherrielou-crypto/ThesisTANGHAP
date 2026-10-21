package com.sti.tanghap.ai;

import com.sti.tanghap.domain.CoughAnalyzer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Advanced Non-diagnostic AI logic for respiratory assessment.
 * Correlates multiple sensor inputs with user history and symptom patterns.
 * All overloads share one rule set, used by both the live screen and the saved session.
 */
public class AssessmentEngine {

    public static final String CATEGORY_PNEUMONIA = "Pneumonia Symptoms Indication";
    public static final String CATEGORY_ASTHMA = "Early Detection of Asthma";
    public static final String CATEGORY_COPD = "COPD Assessment";
    public static final String CATEGORY_BRONCHITIS = "Bronchitis Pattern Recognition";
    public static final String CATEGORY_STANDARD = "Standard Respiratory Evaluation";

    /** Passed as recentCoughCount when the caller does not track cough episodes. */
    private static final int UNKNOWN_COUGH_COUNT = -1;

    public static class AssessmentResult {
        public List<String> possibleFindings = new ArrayList<>();
        public List<String> observations = new ArrayList<>();
        public String insight;
        public String guidance;
        public String otcCategory;
        public String medicalDisclaimer = "AI-assisted respiratory monitoring and preliminary assessment only. DOES NOT medically diagnose diseases.";
        public boolean recommendHospital;
        public boolean recommendClinic;
        public boolean recommendPharmacy;
        public boolean wheezeDetected;

        public AssessmentResult() {}
    }

    public AssessmentResult generatePreliminaryAssessment(
            BreathingAnalyzer.BreathingResult breathing,
            CoughAnalyzer.CoughType coughType,
            boolean hasHistoryOfAsthma,
            boolean hasHistoryOfCOPD,
            boolean hasMildCongestion) {
        return generatePreliminaryAssessment(breathing, coughType, null,
                hasHistoryOfAsthma, hasHistoryOfCOPD, hasMildCongestion);
    }

    /**
     * @param serverSoundLabel the label returned by the Flask sound classifier
     *                         (for example "wheezing", "wet_cough"); null when unavailable.
     */
    public AssessmentResult generatePreliminaryAssessment(
            BreathingAnalyzer.BreathingResult breathing,
            CoughAnalyzer.CoughType coughType,
            String serverSoundLabel,
            boolean hasHistoryOfAsthma,
            boolean hasHistoryOfCOPD,
            boolean hasMildCongestion) {
        return generatePreliminaryAssessment(breathing, coughType, serverSoundLabel, UNKNOWN_COUGH_COUNT,
                hasHistoryOfAsthma, hasHistoryOfCOPD, hasMildCongestion);
    }

    /**
     * @param breathing        breathing analysis (bpm 0 = not measured yet)
     * @param coughType        threshold-based cough type from {@link CoughAnalyzer}
     * @param coughLabel       label from the trained cough model on the Flask server, may be null
     * @param recentCoughCount cough episodes in the recent window, or -1 when unknown
     * @param hasMildCongestion background information only; OTC guidance is given for coughs only
     */
    public AssessmentResult generatePreliminaryAssessment(
            BreathingAnalyzer.BreathingResult breathing,
            CoughAnalyzer.CoughType coughType,
            String coughLabel,
            int recentCoughCount,
            boolean hasHistoryOfAsthma,
            boolean hasHistoryOfCOPD,
            boolean hasMildCongestion) {

        AssessmentResult result = new AssessmentResult();
        String label = coughLabel != null ? coughLabel.toLowerCase(Locale.US) : "";
        int bpm = breathing != null ? breathing.bpm : 0;

        boolean rapidSevere = bpm > 24;
        boolean rapid = bpm > 20;
        boolean severeCough = coughType == CoughAnalyzer.CoughType.SEVERE || label.contains("severe");
        boolean frequentCough = coughType == CoughAnalyzer.CoughType.FREQUENT;
        boolean persistent = label.contains("persistent");
        boolean wetCough = coughType == CoughAnalyzer.CoughType.WET || label.contains("wet");
        boolean dryCough = coughType == CoughAnalyzer.CoughType.DRY || label.contains("dry");
        result.wheezeDetected = label.contains("wheez");

        // 1. PNEUMONIA SYMPTOM INDICATION (BPM > 24 or severe/worsening cough)
        if (rapidSevere || severeCough) {
            result.possibleFindings.add("Preliminary indicators associated with pneumonia-like respiratory abnormalities.");
            if (rapidSevere) result.observations.add(rateObservation(bpm, "above 24 BPM (tachypnea)"));
            if (severeCough) result.observations.add("Severe or worsening coughing episodes detected.");
            result.otcCategory = CATEGORY_PNEUMONIA;
            result.recommendHospital = true; // Shows hospital button for critical state
        }
        // 2. ASTHMA-LIKE EPISODE (wheezing sound, or asthma history + rapid breathing / frequent cough)
        else if (result.wheezeDetected || (hasHistoryOfAsthma && (rapid || frequentCough))) {
            result.possibleFindings.add("Cough patterns potentially associated with an asthma-like respiratory episode.");
            if (result.wheezeDetected) result.observations.add("Sound classifier reported a wheezing-like sound.");
            if (rapid) result.observations.add(rateObservation(bpm, "above the normal 12-20 BPM range"));
            if (frequentCough) result.observations.add(coughCountObservation(recentCoughCount));
            if (hasHistoryOfAsthma) result.observations.add("Profile lists a history of asthma.");
            result.otcCategory = CATEGORY_ASTHMA;
            result.recommendHospital = true; // Shows hospital button for emergency response
        }
        // 3. COPD-RELATED PATTERN (COPD history + frequent/wet/persistent cough or rapid breathing)
        else if (hasHistoryOfCOPD && (frequentCough || wetCough || persistent || rapid)) {
            result.possibleFindings.add("Respiratory patterns possibly associated with COPD-related changes.");
            if (persistent) result.observations.add("Sound classifier reported a persistent cough.");
            if (frequentCough) result.observations.add(coughCountObservation(recentCoughCount));
            if (wetCough) result.observations.add("Wet / productive cough pattern detected.");
            if (rapid) result.observations.add(rateObservation(bpm, "above the normal 12-20 BPM range"));
            result.observations.add("Profile lists a history of COPD.");
            result.otcCategory = CATEGORY_COPD;
            result.recommendClinic = true;
            result.recommendHospital = false; // Hospital button stays for pneumonia-like findings only
        }
        // 4. BRONCHITIS PATTERN RECOGNITION (wet / persistent cough)
        else if (wetCough || persistent) {
            result.possibleFindings.add("Possible respiratory patterns associated with bronchitis-related observations.");
            result.observations.add(persistent
                    ? "Sound classifier reported a persistent cough."
                    : "Wet / productive cough pattern detected.");
            if (rapid) result.observations.add(rateObservation(bpm, "above the normal 12-20 BPM range"));
            result.otcCategory = CATEGORY_BRONCHITIS;
            result.recommendClinic = true;
            result.recommendPharmacy = true;
            result.recommendHospital = false; // Hospital button hidden for mild bronchitis
        }
        // 5. STANDARD RESPIRATORY EVALUATION
        else {
            if (rapid) {
                result.possibleFindings.add("Breathing rate is slightly elevated; no other abnormal pattern detected.");
                result.observations.add(rateObservation(bpm, "above the normal 12-20 BPM range"));
                result.recommendClinic = true;
            } else if (bpm > 0 && bpm < 12) {
                result.possibleFindings.add("Breathing rate is below the typical range; no other abnormal pattern detected.");
                result.observations.add(rateObservation(bpm, "below the normal 12-20 BPM range"));
                result.recommendClinic = true;
            } else {
                result.possibleFindings.add("Respiratory metrics remain within baseline parameters.");
                if (bpm > 0) result.observations.add(rateObservation(bpm, "within the normal 12-20 BPM range"));
            }
            if (dryCough) {
                result.observations.add("Dry / non-productive cough pattern detected.");
            }
            if (result.observations.isEmpty()) {
                result.observations.add("No abnormal breathing or cough pattern detected so far.");
            }

            // Allergies are background information only; OTC guidance is given for coughs only.
            if (dryCough) {
                result.otcCategory = "Cough Suppressants (for non-productive cough)";
                result.recommendPharmacy = true;
            } else {
                result.otcCategory = CATEGORY_STANDARD;
            }
            result.recommendHospital = false;
        }

        // 6. SUMMARY INSIGHT GENERATION
        if (result.recommendHospital) {
            result.guidance = "Immediate clinical evaluation at a hospital or emergency facility is recommended.";
        } else if (result.recommendClinic) {
            result.guidance = "Consider scheduling a check-up at a nearby clinic or consulting a primary physician.";
        } else {
            result.guidance = "Maintain regular respiratory monitoring, rest, and proper hydration.";
        }

        result.insight = "Assessment Summary: " + String.join(" ", result.possibleFindings)
                + "\n\nGuidance: " + result.guidance;
        return result;
    }

    private static String rateObservation(int bpm, String qualifier) {
        return "Measured breathing rate of " + bpm + " BPM is " + qualifier + ".";
    }

    private static String coughCountObservation(int count) {
        return count >= 0
                ? "Frequent coughing detected (" + count + " cough episodes in the last minute)."
                : "Frequent coughing detected.";
    }
}
