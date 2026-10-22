package com.sti.tanghap.domain;

/**
 * Age-based breathing-rate limits shared by the live assessment and the alerts screen.
 * An unknown age (negative) uses the adult values.
 */
public final class RespiratoryNorms {

    private RespiratoryNorms() {}

    /** Normal resting breaths per minute: {min, max} (pediatric vital-sign ranges). */
    public static int[] normalRange(int ageYears) {
        if (ageYears < 0) return new int[]{12, 20};
        if (ageYears < 1) return new int[]{30, 60};
        if (ageYears <= 3) return new int[]{24, 40};
        if (ageYears <= 6) return new int[]{22, 34};
        if (ageYears <= 12) return new int[]{18, 30};
        if (ageYears <= 18) return new int[]{12, 16};
        return new int[]{12, 20};
    }

    /** Above the normal range for this age. */
    public static boolean isAboveNormal(int bpm, int ageYears) {
        return bpm > normalRange(ageYears)[1];
    }

    /** Below the normal range for this age (0 = not measured, never "below"). */
    public static boolean isBelowNormal(int bpm, int ageYears) {
        return bpm > 0 && bpm < normalRange(ageYears)[0];
    }

    /**
     * Fast breathing that warrants checking for pneumonia: WHO IMCI cut-offs of 50/min
     * (2 to 12 months) and 40/min (1 to 5 years); above the normal range for ages 5 to 12;
     * above 24/min for teenagers and adults.
     */
    public static boolean isFastBreathing(int bpm, int ageYears) {
        if (ageYears >= 0 && ageYears < 1) return bpm >= 50;
        if (ageYears >= 1 && ageYears < 5) return bpm >= 40;
        if (ageYears >= 5 && ageYears <= 12) return bpm > normalRange(ageYears)[1];
        return bpm > 24;
    }

    /** e.g. "12-20 BPM" */
    public static String rangeText(int ageYears) {
        int[] r = normalRange(ageYears);
        return r[0] + "-" + r[1] + " BPM";
    }
}
