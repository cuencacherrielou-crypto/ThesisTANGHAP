package com.sti.tanghap.data;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for the locally cached health flags used by the live assessment,
 * derived from the Firestore "healthProfile" map.
 */
public final class HealthPrefs {
    public static final String HEALTH_PREFS = "TanghapUserPreferences";
    public static final String SESSION_PREFS = "TanghapSessionHistory";

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
                .putString("inhaler_usage", usage instanceof String ? (String) usage : "")
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
