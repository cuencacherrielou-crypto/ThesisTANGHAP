package com.sti.tanghap.ai;

import com.sti.tanghap.domain.RespiratoryNorms;
import java.util.ArrayList;
import java.util.List;

/**
 * Advanced Non-diagnostic AI logic for respiratory assessment.
 * Correlates multiple sensor inputs with user history and symptom patterns.
 * All overloads share one rule set, used by both the live screen and the saved session.
 * Conditions are only ever named as a "possibility": the app does not diagnose.
 */
public class AssessmentEngine {

    public static final String CATEGORY_PNEUMONIA = "Possibility of a pneumonia-like condition";
    public static final String CATEGORY_ASTHMA = "Possibility of an asthma-like episode";
    public static final String CATEGORY_COPD = "Possibility of a COPD flare-up";
    public static final String CATEGORY_BRONCHITIS = "Possibility of a bronchitis-like condition";
    public static final String CATEGORY_STANDARD = "No specific condition pattern detected";

    public static final String NOT_A_DIAGNOSIS =
            "This is only a possibility based on the sensor readings, not a diagnosis. Only a doctor can confirm a condition.";

    public static class AssessmentResult {
        public List<String> possibleFindings = new ArrayList<>();
        public List<String> observations = new ArrayList<>();
        public String insight;
        public String guidance;
        /** One of the CATEGORY_* constants, always worded as a possibility. */
        public String possibleCondition;
        /** One of the OtcGuidance keys, already adjusted for the user's age. */
        public String medicineKey;
        /** Medicine guidance text that matches {@link #possibleCondition}. */
        public String medicationGuidance;
        public boolean recommendHospital;
        public boolean recommendClinic;
        public boolean recommendPharmacy;
        public boolean wheezeDetected;

        public AssessmentResult() {}
    }

    /**
     * @param breathing         breathing analysis (bpm 0 = not measured yet)
     * @param cough             latest cough / wheeze class from the on-device cough model,
     *                          null when the model has not heard one yet
     * @param hasMildCongestion background information only; OTC guidance is given for coughs only
     */
    public AssessmentResult generatePreliminaryAssessment(
            BreathingAnalyzer.BreathingResult breathing,
            CoughClass cough,
            boolean hasHistoryOfAsthma,
            boolean hasHistoryOfCOPD,
            boolean hasMildCongestion) {
        return generatePreliminaryAssessment(breathing, cough,
                new PatientProfile(-1, hasHistoryOfAsthma, hasHistoryOfCOPD, hasMildCongestion));
    }

    public AssessmentResult generatePreliminaryAssessment(
            BreathingAnalyzer.BreathingResult breathing,
            CoughClass cough,
            PatientProfile profile) {

        if (profile == null) profile = new PatientProfile();
        AssessmentResult result = new AssessmentResult();
        int bpm = breathing != null ? breathing.bpm : 0;
        int age = profile.ageYears;
        String range = RespiratoryNorms.rangeText(age);

        // Breathing-rate limits depend on age (adult limits when the age is unknown)
        boolean fastBreathing = bpm > 0 && RespiratoryNorms.isFastBreathing(bpm, age);
        boolean rapid = bpm > 0 && RespiratoryNorms.isAboveNormal(bpm, age);
        boolean slow = RespiratoryNorms.isBelowNormal(bpm, age);
        boolean severeCough = cough == CoughClass.SEVERE_COUGH;
        boolean persistent = cough == CoughClass.PERSISTENT_COUGH;
        boolean wetCough = cough == CoughClass.WET_COUGH;
        boolean dryCough = cough == CoughClass.DRY_COUGH;
        result.wheezeDetected = cough == CoughClass.WHEEZING;
        String medicine;

        // 1. PNEUMONIA-LIKE (fast breathing for the user's age, or severe/worsening cough)
        if (fastBreathing || severeCough) {
            result.possibleFindings.add(CATEGORY_PNEUMONIA
                    + ": the readings show signs that can occur with a lung infection.");
            if (fastBreathing) result.observations.add(rateObservation(bpm, "fast for your age (normal resting rate " + range + ")"));
            if (severeCough) result.observations.add("Severe or worsening coughing episodes detected.");
            result.possibleCondition = CATEGORY_PNEUMONIA;
            medicine = OtcGuidance.SEEK_CARE;
            result.recommendHospital = true; // Shows hospital button for critical state
        }
        // 2. ASTHMA-LIKE EPISODE (wheezing sound, or asthma history + rapid breathing / persistent cough)
        else if (result.wheezeDetected || (profile.hasAsthma && (rapid || persistent))) {
            result.possibleFindings.add(CATEGORY_ASTHMA
                    + ": the readings show signs that can occur when the airways narrow.");
            if (result.wheezeDetected) result.observations.add("Sound classifier reported a wheezing-like sound.");
            if (rapid) result.observations.add(rateObservation(bpm, "above the normal " + range + " range for your age"));
            if (persistent) result.observations.add("Sound classifier reported a persistent cough.");
            if (profile.hasAsthma) result.observations.add("Profile lists a history of asthma.");
            result.possibleCondition = CATEGORY_ASTHMA;
            medicine = OtcGuidance.PRESCRIBED_RELIEVER;
            result.recommendHospital = true; // Shows hospital button for emergency response
        }
        // 3. COPD FLARE-UP (COPD history + wet/persistent cough or rapid breathing)
        else if (profile.hasCopd && (wetCough || persistent || rapid)) {
            result.possibleFindings.add(CATEGORY_COPD
                    + ": the readings are worse than expected for someone with COPD.");
            if (persistent) result.observations.add("Sound classifier reported a persistent cough.");
            if (wetCough) result.observations.add("Wet / productive cough pattern detected.");
            if (rapid) result.observations.add(rateObservation(bpm, "above the normal " + range + " range for your age"));
            result.observations.add("Profile lists a history of COPD.");
            result.possibleCondition = CATEGORY_COPD;
            medicine = OtcGuidance.COPD_ACTION_PLAN;
            result.recommendClinic = true;
            result.recommendHospital = false; // Hospital button stays for pneumonia-like findings only
        }
        // 4. BRONCHITIS-LIKE (wet / persistent cough)
        else if (wetCough || persistent) {
            result.possibleFindings.add(CATEGORY_BRONCHITIS
                    + ": the cough pattern can occur when the airways are irritated or inflamed.");
            result.observations.add(persistent
                    ? "Sound classifier reported a persistent cough."
                    : "Wet / productive cough pattern detected.");
            if (rapid) result.observations.add(rateObservation(bpm, "above the normal " + range + " range for your age"));
            result.possibleCondition = CATEGORY_BRONCHITIS;
            // A persistent cough needs a doctor; only a wet cough gets OTC medicine
            medicine = persistent ? OtcGuidance.PERSISTENT_COUGH : OtcGuidance.WET_COUGH;
            result.recommendClinic = true;
            result.recommendPharmacy = true;
            result.recommendHospital = false; // Hospital button hidden for mild bronchitis
        }
        // 5. NO SPECIFIC CONDITION PATTERN
        else {
            if (rapid) {
                result.possibleFindings.add("Breathing rate is above the typical range for your age; no specific condition pattern detected.");
                result.observations.add(rateObservation(bpm, "above the normal " + range + " range for your age"));
                result.recommendClinic = true;
            } else if (slow) {
                result.possibleFindings.add("Breathing rate is below the typical range for your age; no specific condition pattern detected.");
                result.observations.add(rateObservation(bpm, "below the normal " + range + " range for your age"));
                result.recommendClinic = true;
            } else {
                result.possibleFindings.add("Respiratory metrics remain within baseline parameters.");
                if (bpm > 0) result.observations.add(rateObservation(bpm, "within the normal " + range + " range for your age"));
            }
            if (dryCough) {
                result.observations.add("Dry / non-productive cough pattern detected.");
            }
            if (result.observations.isEmpty()) {
                result.observations.add("No abnormal breathing or cough pattern detected so far.");
            }

            // Allergies are background information only; OTC guidance is given for coughs only.
            result.possibleCondition = CATEGORY_STANDARD;
            medicine = dryCough ? OtcGuidance.DRY_COUGH : OtcGuidance.NO_MEDICINE;
            result.recommendPharmacy = dryCough;
            result.recommendHospital = false;
        }

        addProfileContext(result, profile);

        // The pharmacy is only suggested when the guidance actually names an OTC medicine
        result.medicineKey = OtcGuidance.select(medicine, age);
        if (!OtcGuidance.suggestsMedicine(result.medicineKey)) result.recommendPharmacy = false;
        result.medicationGuidance = OtcGuidance.describe(result.medicineKey, profile.hasAsthma || profile.hasCopd);

        // 6. SUMMARY INSIGHT GENERATION
        if (result.recommendHospital) {
            result.guidance = "Immediate clinical evaluation at a hospital or emergency facility is recommended.";
        } else if (result.recommendClinic) {
            result.guidance = "Consider scheduling a check-up at a nearby clinic or consulting a primary physician.";
        } else {
            result.guidance = "Maintain regular respiratory monitoring, rest, and proper hydration.";
        }

        StringBuilder insight = new StringBuilder("Assessment Summary: ")
                .append(String.join(" ", result.possibleFindings))
                .append("\n\nGuidance: ").append(result.guidance);
        if (!CATEGORY_STANDARD.equals(result.possibleCondition)) {
            insight.append("\n\n").append(NOT_A_DIAGNOSIS);
        }
        result.insight = insight.toString();
        return result;
    }

    /** Profile answers that make a finding more important to act on. */
    private static void addProfileContext(AssessmentResult result, PatientProfile profile) {
        if (!result.recommendHospital && !result.recommendClinic) return;
        if (profile.smokesOrVapes) {
            result.observations.add("Profile: smokes or vapes, which can make breathing symptoms worse.");
        }
        if (profile.previouslyHospitalized) {
            result.observations.add("Profile: past hospital stay for breathing problems; do not delay getting checked.");
        }
    }

    private static String rateObservation(int bpm, String qualifier) {
        return "Measured breathing rate of " + bpm + " BPM is " + qualifier + ".";
    }
}
