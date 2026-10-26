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

    /** Persistent cough is measured from the cough history (see CoughHistory), not from one cough's sound. */
    public static final String PERSISTENT_OBSERVATION =
            "Coughs have been recorded on and off for 3 weeks or more (persistent cough).";
    /** Severe cough is measured from the cough history too: coughing fits during a cough of 2 weeks or more (CDC). */
    public static final String SEVERE_OBSERVATION =
            "Coughing fits (many coughs in a row) were recorded during a cough lasting 2 weeks or more (severe cough).";

    public static final String GUIDANCE_HOSPITAL =
            "Immediate clinical evaluation at a hospital or emergency facility is recommended.";
    public static final String GUIDANCE_CLINIC =
            "Consider scheduling a check-up at a nearby clinic or consulting a primary physician.";
    public static final String GUIDANCE_ROUTINE =
            "Maintain regular respiratory monitoring, rest, and proper hydration.";

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
     * One sound only, without the session's other coughs (e.g. before any sound is heard); see
     * {@link #assess}. One sound names no condition on its own. PERSISTENT_COUGH stands for a
     * cough history of 3 weeks or more.
     *
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
        return assess(breathing, CoughEvidence.single(cough), profile);
    }

    /**
     * Each possible condition is named only when ALL of its signs are present together, following
     * the clinical criteria below as far as the device can observe them. Where a sign cannot be
     * measured (temperature, chest examination, lung function), the app asks for a doctor's check
     * instead of naming the condition. Only a high-confidence breathing pattern counts (its bpm is
     * passed on; a medium or low one arrives as bpm 0).
     *
     * @param breathing breathing pattern from the breathing sounds (bpm 0 = not measured or not sure)
     * @param ev        the session's coughs and wheezes with the user's earlier sessions
     */
    public AssessmentResult assess(BreathingAnalyzer.BreathingResult breathing, CoughEvidence ev,
                                   PatientProfile profile) {
        if (profile == null) profile = new PatientProfile();
        if (ev == null) ev = new CoughEvidence();
        AssessmentResult result = new AssessmentResult();
        int bpm = breathing != null ? breathing.bpm : 0;
        int age = profile.ageYears;
        String range = RespiratoryNorms.rangeText(age);

        // Breathing-rate limits depend on age (adult limits when the age is unknown)
        boolean fastBreathing = bpm > 0 && RespiratoryNorms.isFastBreathing(bpm, age);
        boolean rapid = bpm > 0 && RespiratoryNorms.isAboveNormal(bpm, age);
        boolean slow = RespiratoryNorms.isBelowNormal(bpm, age);
        boolean coughing = ev.coughs > 0;
        boolean productive = ev.productive();
        boolean severe = ev.severe;
        boolean repeatedWheeze = ev.repeatedWheeze();
        CoughClass mainType = ev.mainCoughType();
        result.wheezeDetected = ev.wheezes > 0;
        String medicine;

        // COPD flare-up signs (GOLD 2024 exacerbation; Anthonisen 1987): more coughing, phlegm,
        // breathlessness. Two of the three are needed.
        boolean moreCoughing = ev.continuous || severe || ev.persistent;
        int copdSigns = (moreCoughing ? 1 : 0) + (productive ? 1 : 0) + (rapid ? 1 : 0);

        // 1. PNEUMONIA-LIKE (WHO IMCI 2014: cough with fast breathing for the age). Needs repeated
        //    coughing AND surely fast breathing. Either one alone is checked at a clinic instead
        //    (branch 5), so one misheard cough or a fast rate after exertion does not send the user
        //    to hospital. Temperature and the chest exam cannot be measured, so a doctor confirms.
        if (fastBreathing && ev.coughs >= CoughEvidence.PNEUMONIA_MIN_COUGHS) {
            result.possibleFindings.add(CATEGORY_PNEUMONIA
                    + ": the readings show signs that can occur with a lung infection.");
            result.observations.add(rateObservation("fast for your age (normal resting rate " + range + ")"));
            result.observations.add(coughsObservation(ev) + " together with the fast breathing.");
            result.possibleCondition = CATEGORY_PNEUMONIA;
            medicine = OtcGuidance.SEEK_CARE;
            result.recommendHospital = true; // Shows hospital button for critical state
        }
        // 2. ASTHMA-LIKE EPISODE (GINA 2024: wheeze with other respiratory symptoms that vary over
        //    time). Without an asthma history all three are needed: wheezing heard repeatedly in
        //    this session, wheezing on an earlier day too (recurrent), and another symptom now
        //    (coughing or rapid breathing). With an asthma history (already confirmed by a doctor),
        //    repeated wheezing, a persistent cough, or rapid breathing with coughing suggests an
        //    episode. Wheezing in one session alone is only noted (branch 5).
        else if ((profile.hasAsthma && (repeatedWheeze || ev.persistent || (rapid && coughing)))
                || (repeatedWheeze && ev.wheezeOnEarlierDay && (coughing || rapid))) {
            result.possibleFindings.add(CATEGORY_ASTHMA
                    + ": the readings show signs that can occur when the airways narrow.");
            if (ev.wheezes > 0) result.observations.add(wheezeObservation(ev));
            if (ev.wheezeOnEarlierDay) result.observations.add("Wheezing was also heard on an earlier day in the last 4 weeks.");
            if (coughing) result.observations.add(coughsObservation(ev) + ".");
            if (rapid) result.observations.add(rateObservation("above the normal " + range + " range for your age"));
            if (ev.persistent) result.observations.add(PERSISTENT_OBSERVATION);
            if (profile.hasAsthma) result.observations.add("Profile lists a history of asthma.");
            result.possibleCondition = CATEGORY_ASTHMA;
            medicine = OtcGuidance.PRESCRIBED_RELIEVER;
            // Like branch 1, the hospital needs airway signs WITH surely rapid breathing
            result.recommendHospital = rapid || fastBreathing;
            result.recommendClinic = !result.recommendHospital;
        }
        // 3. COPD FLARE-UP: COPD history AND at least two of the flare-up signs above
        else if (profile.hasCopd && copdSigns >= 2) {
            result.possibleFindings.add(CATEGORY_COPD
                    + ": the readings are worse than expected for someone with COPD.");
            if (ev.persistent) result.observations.add(PERSISTENT_OBSERVATION);
            else if (ev.continuous) result.observations.add("Coughing more than usual: 3 or more coughing episodes in 24 hours, or over an hour of coughing.");
            if (productive) result.observations.add(productiveObservation(ev));
            if (severe) result.observations.add(SEVERE_OBSERVATION);
            if (rapid) result.observations.add(rateObservation("above the normal " + range + " range for your age"));
            result.observations.add("Profile lists a history of COPD.");
            result.possibleCondition = CATEGORY_COPD;
            medicine = OtcGuidance.COPD_ACTION_PLAN;
            result.recommendClinic = true;
            result.recommendHospital = false; // Hospital button stays for pneumonia-like findings only
        }
        // 4. BRONCHITIS-LIKE (AAFP 2016 acute bronchitis: a cough lasting more than 5 days, often
        //    with phlegm, without pneumonia signs). Needs BOTH a productive cough (most coughs
        //    sounding wet, repeatedly) AND coughing that has gone on for more than 5 days. Fast
        //    breathing with the coughing was already sent to branch 1. A severe cough (coughing fits
        //    for 2 weeks or more, the CDC pertussis signs) is checked by a doctor instead (branch 5).
        else if (productive && ev.lastingCough() && !severe) {
            result.possibleFindings.add(CATEGORY_BRONCHITIS
                    + ": the cough pattern can occur when the airways are irritated or inflamed.");
            result.observations.add(productiveObservation(ev));
            result.observations.add(ev.persistent ? PERSISTENT_OBSERVATION
                    : "Coughing has gone on for " + ev.coughSpellDays + " days (more than "
                    + CoughEvidence.LASTING_COUGH_DAYS + " days).");
            result.possibleCondition = CATEGORY_BRONCHITIS;
            // A cough of 3 weeks or more needs a doctor; a shorter wet cough gets OTC medicine
            medicine = ev.persistent ? OtcGuidance.PERSISTENT_COUGH : OtcGuidance.WET_COUGH;
            result.recommendClinic = true;
            result.recommendPharmacy = true;
            result.recommendHospital = false; // Hospital button hidden for mild bronchitis
        }
        // 5. NO SPECIFIC CONDITION PATTERN: single signs are reported, and the cough type still
        //    gets its OTC guidance, but no condition is named
        else {
            if (rapid || fastBreathing) {
                result.possibleFindings.add("Breathing rate is above the typical range for your age; no specific condition pattern detected.");
                result.observations.add(rateObservation("above the normal " + range + " range for your age"));
                result.recommendClinic = true;
            } else if (slow) {
                result.possibleFindings.add("Breathing rate is below the typical range for your age; no specific condition pattern detected.");
                result.observations.add(rateObservation("below the normal " + range + " range for your age"));
                result.recommendClinic = true;
            } else {
                if (!severe && !ev.persistent) result.possibleFindings.add("Respiratory metrics remain within baseline parameters.");
                if (bpm > 0) result.observations.add(rateObservation("within the normal " + range + " range for your age"));
            }
            if (coughing) result.observations.add(coughsObservation(ev) + ".");
            if (ev.persistent) {
                // A cough of 3 weeks or more needs a doctor to find its cause, whatever it sounds like
                result.possibleFindings.add("A cough lasting 3 weeks or more: have it checked by a doctor.");
                result.observations.add(PERSISTENT_OBSERVATION);
                result.recommendClinic = true;
            }
            if (severe) {
                // CDC: a cough of 2 weeks or more with coughing fits can be whooping cough (pertussis),
                // which needs a doctor (prescribed antibiotics; it spreads to others); no OTC medicine
                result.possibleFindings.add("Severe cough: coughing fits during a cough lasting 2 weeks or more. "
                        + "Have it checked by a doctor soon, and keep away from babies until then.");
                result.observations.add(SEVERE_OBSERVATION);
                result.recommendClinic = true;
            }
            if (ev.wheezes > 0) {
                result.observations.add(wheezeObservation(ev) + " Wheezing in one session is not enough to "
                        + "suggest asthma; it is checked again in later sessions. See a doctor if you feel short of breath.");
            }
            if (result.observations.isEmpty()) {
                result.observations.add("No abnormal breathing or cough pattern detected so far.");
            }

            // Allergies are background information only; OTC guidance is given for coughs only.
            result.possibleCondition = CATEGORY_STANDARD;
            if (severe) medicine = OtcGuidance.SEEK_CARE;
            else if (ev.persistent) medicine = OtcGuidance.PERSISTENT_COUGH;
            else if (mainType == CoughClass.WET_COUGH) medicine = OtcGuidance.WET_COUGH;
            else if (mainType == CoughClass.DRY_COUGH) medicine = OtcGuidance.DRY_COUGH;
            else medicine = OtcGuidance.NO_MEDICINE;
            result.recommendPharmacy = OtcGuidance.WET_COUGH.equals(medicine) || OtcGuidance.DRY_COUGH.equals(medicine);
            result.recommendHospital = false;
        }

        addProfileContext(result, profile);

        // The pharmacy is only suggested when the guidance actually names an OTC medicine
        result.medicineKey = OtcGuidance.select(medicine, age);
        if (!OtcGuidance.suggestsMedicine(result.medicineKey)) result.recommendPharmacy = false;
        result.medicationGuidance = OtcGuidance.describe(result.medicineKey, profile.hasAsthma || profile.hasCopd);

        // 6. SUMMARY INSIGHT GENERATION
        if (result.recommendHospital) {
            result.guidance = GUIDANCE_HOSPITAL;
        } else if (result.recommendClinic) {
            result.guidance = GUIDANCE_CLINIC;
        } else {
            result.guidance = GUIDANCE_ROUTINE;
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

    /** The rate is estimated from breathing sounds; only how it compares is shown, not the number. */
    private static String rateObservation(String qualifier) {
        return "Breathing, judged from its sounds, is " + qualifier + ".";
    }

    /** e.g. "5 coughs detected (4 wet, 1 dry)", without the closing period ("severe" only in older sessions). */
    private static String coughsObservation(CoughEvidence ev) {
        List<String> parts = new ArrayList<>();
        if (ev.count(CoughClass.DRY_COUGH) > 0) parts.add(ev.count(CoughClass.DRY_COUGH) + " dry");
        if (ev.count(CoughClass.WET_COUGH) > 0) parts.add(ev.count(CoughClass.WET_COUGH) + " wet");
        if (ev.count(CoughClass.SEVERE_COUGH) > 0) parts.add(ev.count(CoughClass.SEVERE_COUGH) + " severe");
        return ev.coughs + (ev.coughs == 1 ? " cough" : " coughs") + " detected (" + String.join(", ", parts) + ")";
    }

    private static String productiveObservation(CoughEvidence ev) {
        return "Wet / productive cough pattern: " + ev.count(CoughClass.WET_COUGH) + " of " + ev.coughs
                + " coughs sounded wet.";
    }

    private static String wheezeObservation(CoughEvidence ev) {
        return ev.wheezes == 1 ? "A wheezing-like sound was heard once."
                : "Wheezing-like sounds were heard " + ev.wheezes + " times.";
    }
}
