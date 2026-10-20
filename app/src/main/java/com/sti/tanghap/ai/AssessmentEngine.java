package com.sti.tanghap.ai;

import com.sti.tanghap.domain.CoughAnalyzer;
import java.util.ArrayList;
import java.util.List;

/**
 * Advanced Non-diagnostic AI logic for respiratory assessment.
 * Correlates multiple sensor inputs with user history and symptom patterns.
 */
public class AssessmentEngine {

    public static class AssessmentResult {
        public List<String> possibleFindings = new ArrayList<>();
        public List<String> observations = new ArrayList<>();
        public String insight;
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

        AssessmentResult result = new AssessmentResult();
        String serverLabel = serverSoundLabel != null ? serverSoundLabel.toLowerCase() : "";
        // Combine the on-device cough type with the server's sound label so labels such as
        // "wheezing" or "persistent" reach the rules below.
        String predictedCough = ((coughType != null) ? coughType.name().toLowerCase() : "") + " " + serverLabel;
        result.wheezeDetected = serverLabel.contains("wheez");

        // 1. PNEUMONIA SYMPTOM INDICATION (High BPM > 24 / Severe or Deep Cough)
        if (breathing.bpm > 24 || predictedCough.contains("severe") || coughType == CoughAnalyzer.CoughType.SEVERE) {
            result.possibleFindings.add("Preliminary indicators associated with pneumonia-like respiratory abnormalities.");
            result.observations.add("Tachypnea (rapid breathing) and severe/deep coughing episodes observed.");
            result.observations.add("Thick or discolored mucus indicators correlated with rapid respiration rate.");
            result.otcCategory = "Pneumonia Symptoms Indication";
            result.recommendHospital = true; // Shows hospital button for critical state
        }
        // 2. ASTHMA ATTACK EPISODE (Wheezing Sound / Rapid Breathing + Asthma Baseline)
        else if (result.wheezeDetected ||
                (hasHistoryOfAsthma && (breathing.bpm > 20 || coughType == CoughAnalyzer.CoughType.FREQUENT))) {
            result.possibleFindings.add("Cough patterns potentially associated with an asthma-like respiratory episode.");
            result.observations.add("High-pitched whistling/wheezing sound or elevated inhalation cycles detected.");
            result.observations.add("Symptoms indicate potential airway narrowing or chest tightness.");
            result.otcCategory = "Early Detection of Asthma";
            result.recommendHospital = true; // Shows hospital button for emergency response
        }
        // 3. COPD-RELATED PATTERN (COPD Baseline + Frequent/Wet Cough or Rapid Breathing)
        else if (hasHistoryOfCOPD &&
                (coughType == CoughAnalyzer.CoughType.FREQUENT
                        || coughType == CoughAnalyzer.CoughType.WET
                        || predictedCough.contains("persistent")
                        || breathing.bpm > 20)) {
            result.possibleFindings.add("Respiratory patterns possibly associated with COPD-related changes.");
            result.observations.add("Frequent or productive coughing, or an elevated breathing rate, detected in a user with a COPD history.");
            result.observations.add("Changes may indicate increased airway irritation compared with the user's usual pattern.");
            result.otcCategory = "COPD Assessment";
            result.recommendClinic = true;
            result.recommendHospital = false; // Hospital button stays for pneumonia-like findings only
        }
        // 4. BRONCHITIS PATTERN RECOGNITION (Wet / Persistent Cough + Elevated Breathing)
        else if (predictedCough.contains("wet") || predictedCough.contains("persistent") ||
                coughType == CoughAnalyzer.CoughType.WET || breathing.bpm > 18) {
            result.possibleFindings.add("Possible respiratory patterns associated with bronchitis-related observations.");
            result.observations.add("Persistent productive cough starting dry and transitioning to clear/discolored mucus.");
            result.observations.add("Increased respiratory effort noted following frequent coughing cycles.");
            result.otcCategory = "Bronchitis Pattern Recognition";
            result.recommendClinic = true;
            result.recommendPharmacy = true;
            result.recommendHospital = false; // Hospital button hidden for mild bronchitis
        }
        // 5. STANDARD RESPIRATORY EVALUATION (Default / Stable Baseline)
        else {
            result.possibleFindings.add("Respiratory metrics remain within baseline parameters.");
            result.observations.add("Normal breathing rhythm and standard acoustic spectral profile detected.");
            if (hasMildCongestion) {
                result.otcCategory = "Antihistamines (for mild congestion)";
                result.recommendPharmacy = true;
            } else if (coughType == CoughAnalyzer.CoughType.DRY) {
                result.otcCategory = "Cough Suppressants (for non-productive cough)";
                result.recommendPharmacy = true;
            } else {
                result.otcCategory = "Standard Respiratory Evaluation";
            }
            result.recommendHospital = false;
        }

        // 6. SUMMARY INSIGHT GENERATION
        StringBuilder sb = new StringBuilder();
        sb.append("Assessment Summary: ");
        if (result.possibleFindings.isEmpty()) {
            sb.append("Respiratory patterns appear stable and within the preliminary baseline.");
        } else {
            sb.append(String.join(" ", result.possibleFindings));
        }

        sb.append("\n\nGuidance: ");
        if (result.recommendHospital) {
            sb.append("Immediate clinical evaluation at a hospital or emergency facility is recommended.");
        } else if (result.recommendClinic) {
            sb.append("Consider scheduling a check-up at a nearby clinic or consulting a primary physician.");
        } else {
            sb.append("Maintain regular respiratory monitoring, rest, and proper hydration.");
        }

        result.insight = sb.toString();
        return result;
    }
}