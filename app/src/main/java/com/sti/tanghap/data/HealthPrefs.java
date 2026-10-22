package com.sti.tanghap.data;

import android.content.Context;
import android.content.SharedPreferences;
import com.sti.tanghap.ai.PatientProfile;
import com.sti.tanghap.domain.AgeCalculator;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for the locally cached health flags used by the live assessment,
 * derived from the Firestore "healthProfile" map.
 */
public final class HealthPrefs {
    public static final String HEALTH_PREFS = "TanghapUserPreferences";
    public static final String SESSION_PREFS = "TanghapSessionHistory";

    private static final String KEY_SMOKER = "smokes_or_vapes";
    private static final String KEY_HOSPITALIZED = "previously_hospitalized";
    private static final String KEY_BIRTHDAY = "birthday";
    private static final String KEY_AGE = "age";
    private static final String KEY_COUGH_REFERENCE_DB = "cough_reference_db";

    private HealthPrefs() {}

    @SuppressWarnings("unchecked")
    public static void saveFromHealthMap(Context ctx, Map<String, Object> healthMap) {
        if (healthMap == null) return;

        List<String> conditions = asStringList(healthMap.get("diagnosedConditions"));
        List<String> triggers = asStringList(healthMap.get("respiratoryTriggers"));

        boolean hasAsthma = conditions != null && conditions.contains("Asthma");
        boolean hasCopd = conditions != null && conditions.contains("COPD");
        boolean hasBronchitis = conditions != null && conditions.contains("Bronchitis");
        // Congestion = diagnosed allergies OR dust/pollen listed as a trigger
        boolean hasCongestion = (conditions != null
                && (conditions.contains("Allergies") || conditions.contains("Mild Congestion")))
                || (triggers != null && (triggers.contains("Dust") || triggers.contains("Pollen")));

        Object usage = healthMap.get("inhalerUsageFreqPerDay");

        ctx.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("has_asthma", hasAsthma)
                .putBoolean("has_copd", hasCopd)
                .putBoolean("has_bronchitis", hasBronchitis)
                .putBoolean("has_congestion", hasCongestion)
                .putBoolean(KEY_SMOKER, Boolean.TRUE.equals(healthMap.get("isSmokerOrVaper")))
                .putBoolean(KEY_HOSPITALIZED, Boolean.TRUE.equals(healthMap.get("previouslyHospitalized")))
                .putString("inhaler_usage", usage instanceof String ? (String) usage : "")
                .apply();
    }

    /** Caches the birthday and age from the Firestore user document (top-level fields). */
    public static void saveBirthdayAndAge(Context ctx, Object birthday, Object age) {
        ctx.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_BIRTHDAY, birthday != null ? String.valueOf(birthday) : "")
                .putString(KEY_AGE, age != null ? String.valueOf(age) : "")
                .apply();
    }

    /** The cached answers the assessment uses; the age is worked out fresh from the birthday. */
    public static PatientProfile loadProfile(Context ctx) {
        SharedPreferences pref = ctx.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE);
        PatientProfile p = new PatientProfile();
        p.ageYears = AgeCalculator.resolve(pref.getString(KEY_BIRTHDAY, null),
                pref.getString(KEY_AGE, null), Calendar.getInstance());
        p.hasAsthma = pref.getBoolean("has_asthma", false);
        p.hasCopd = pref.getBoolean("has_copd", false);
        p.hasCongestion = pref.getBoolean("has_congestion", false);
        p.smokesOrVapes = pref.getBoolean(KEY_SMOKER, false);
        p.previouslyHospitalized = pref.getBoolean(KEY_HOSPITALIZED, false);
        return p;
    }

    /** The user's calibrated cough level in dBFS from the last calibration, or null. */
    public static Float coughReferenceDb(Context ctx) {
        SharedPreferences pref = ctx.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE);
        return pref.contains(KEY_COUGH_REFERENCE_DB) ? pref.getFloat(KEY_COUGH_REFERENCE_DB, 0f) : null;
    }

    public static void saveCoughReferenceDb(Context ctx, float levelDb) {
        ctx.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_COUGH_REFERENCE_DB, levelDb)
                .apply();
    }

    /** Removes everything cached for the signed-in user. Call on logout. */
    public static void clearUserData(Context ctx) {
        ctx.getSharedPreferences(HEALTH_PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        ctx.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        return o instanceof List ? (List<String>) o : null;
    }
}
