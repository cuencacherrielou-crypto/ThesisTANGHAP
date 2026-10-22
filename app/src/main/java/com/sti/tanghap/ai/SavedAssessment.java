package com.sti.tanghap.ai;

import com.sti.tanghap.data.SessionEntity;

/**
 * Condition and medicine text for a saved session. Sessions saved before the "possibility"
 * wording stored an old category name in otcRecommendation and no medicine text; those are
 * mapped to the current wording and guidance here so history shows them the same way.
 */
public final class SavedAssessment {

    public final String possibleCondition;
    public final String medicationGuidance;

    private SavedAssessment(String possibleCondition, String medicationGuidance) {
        this.possibleCondition = possibleCondition;
        this.medicationGuidance = medicationGuidance;
    }

    /**
     * @param profile the current profile, used only for older sessions (age limits on medicine)
     */
    public static SavedAssessment of(SessionEntity s, PatientProfile profile) {
        if (profile == null) profile = new PatientProfile();
        if (s.possibleCondition != null) {
            return new SavedAssessment(s.possibleCondition, s.otcRecommendation);
        }

        String old = s.otcRecommendation;
        if (old == null) return new SavedAssessment(null, null);
        String condition;
        String medicine;
        switch (old) {
            case "Pneumonia Symptoms Indication":
                condition = AssessmentEngine.CATEGORY_PNEUMONIA;
                medicine = OtcGuidance.SEEK_CARE;
                break;
            case "Early Detection of Asthma":
                condition = AssessmentEngine.CATEGORY_ASTHMA;
                medicine = OtcGuidance.PRESCRIBED_RELIEVER;
                break;
            case "COPD Assessment":
                condition = AssessmentEngine.CATEGORY_COPD;
                medicine = OtcGuidance.COPD_ACTION_PLAN;
                break;
            case "Bronchitis Pattern Recognition":
                condition = AssessmentEngine.CATEGORY_BRONCHITIS;
                boolean persistent = s.coughFinding != null
                        && s.coughFinding.toLowerCase(java.util.Locale.ROOT).contains("persistent");
                medicine = persistent ? OtcGuidance.PERSISTENT_COUGH : OtcGuidance.WET_COUGH;
                break;
            case "Cough Suppressants (for non-productive cough)":
                condition = AssessmentEngine.CATEGORY_STANDARD;
                medicine = OtcGuidance.DRY_COUGH;
                break;
            case "Standard Respiratory Evaluation":
                condition = AssessmentEngine.CATEGORY_STANDARD;
                medicine = OtcGuidance.NO_MEDICINE;
                break;
            default:
                // Already guidance text from a newer build without the condition column
                return new SavedAssessment(null, old);
        }
        String key = OtcGuidance.select(medicine, profile.ageYears);
        return new SavedAssessment(condition, OtcGuidance.describe(key, profile.hasAsthma || profile.hasCopd));
    }
}
