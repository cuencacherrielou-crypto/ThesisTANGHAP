package com.sti.tanghap.ai;

/**
 * What the user should do after an assessment, as one short headline that can be read at a
 * glance. It follows the assessment's own recommendation (hospital, clinic or routine care).
 */
public enum CareAction {
    HOSPITAL("Go to a hospital now",
            "Warning signs were found together. Get checked at a hospital or emergency facility today."),
    CLINIC("See a doctor soon",
            "Have these readings checked at a clinic or by your doctor."),
    MONITOR("No urgent action needed",
            "Keep monitoring, rest and drink enough fluids.");

    public final String headline;
    public final String advice;

    CareAction(String headline, String advice) {
        this.headline = headline;
        this.advice = advice;
    }

    /**
     * @param recommendHospital the saved hospital recommendation (SessionEntity.riskLevelHigh)
     * @param guidance          the saved guidance sentence (SessionEntity.recommendation), may be null
     * @param possibleCondition the saved possible condition, may be null
     */
    public static CareAction of(boolean recommendHospital, String guidance, String possibleCondition) {
        if (recommendHospital) return HOSPITAL;
        if (guidance != null) {
            return AssessmentEngine.GUIDANCE_CLINIC.equals(guidance) ? CLINIC : MONITOR;
        }
        // Sessions saved without guidance: a named condition still needs a doctor's check
        return possibleCondition != null && !AssessmentEngine.CATEGORY_STANDARD.equals(possibleCondition)
                ? CLINIC : MONITOR;
    }
}
