package com.sti.tanghap.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * Local copy of the profile fields the app needs while offline:
 * emergency contact, prescribed inhaler doses and medications.
 * Firestore stays the source of truth; this mirrors it in SharedPreferences.
 */
public final class ProfilePrefs {

    public static final String PREFS = "TanghapUserPreferences";

    public static final String KEY_EMERGENCY_NAME = "emergency_name";
    public static final String KEY_EMERGENCY_RELATION = "emergency_relation";
    public static final String KEY_EMERGENCY_PHONE = "emergency_phone";
    public static final String KEY_PRESCRIBED_DOSES = "prescribed_doses_per_day";
    public static final String KEY_MEDICATIONS = "medications";

    private ProfilePrefs() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String emergencyName(Context ctx) {
        return prefs(ctx).getString(KEY_EMERGENCY_NAME, "");
    }

    public static String emergencyRelation(Context ctx) {
        return prefs(ctx).getString(KEY_EMERGENCY_RELATION, "");
    }

    public static String emergencyPhone(Context ctx) {
        return prefs(ctx).getString(KEY_EMERGENCY_PHONE, "");
    }

    public static boolean hasEmergencyContact(Context ctx) {
        return !emergencyPhone(ctx).isEmpty();
    }

    /** Prescribed inhaler doses per day; 0 when the user has not set one. */
    public static int prescribedDoses(Context ctx) {
        return prefs(ctx).getInt(KEY_PRESCRIBED_DOSES, 0);
    }

    /** Parses a user-typed dose count such as "2" or "2 times/day"; returns 0 when none is found. */
    public static int parseDoses(String text) {
        if (text == null) return 0;
        StringBuilder digits = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (Character.isDigit(ch)) digits.append(ch);
            else if (digits.length() > 0) break;
        }
        if (digits.length() == 0) return 0;
        try {
            return Math.min(Integer.parseInt(digits.toString()), 24);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Copies emergency contact and medication fields from a Firestore user document into prefs. */
    @SuppressWarnings("unchecked")
    public static void syncFromUserDoc(Context ctx, Map<String, Object> doc) {
        if (doc == null) return;
        SharedPreferences.Editor editor = prefs(ctx).edit();

        Object contactObj = doc.get("emergencyContact");
        if (contactObj instanceof Map) {
            Map<String, Object> contact = (Map<String, Object>) contactObj;
            editor.putString(KEY_EMERGENCY_NAME, str(contact.get("name")));
            editor.putString(KEY_EMERGENCY_RELATION, str(contact.get("relation")));
            editor.putString(KEY_EMERGENCY_PHONE, str(contact.get("phone")));
        }

        Object healthObj = doc.get("healthProfile");
        if (healthObj instanceof Map) {
            Map<String, Object> health = (Map<String, Object>) healthObj;
            Object doses = health.get("prescribedDosesPerDay");
            if (doses instanceof Number) {
                editor.putInt(KEY_PRESCRIBED_DOSES, ((Number) doses).intValue());
            } else if (doses != null) {
                editor.putInt(KEY_PRESCRIBED_DOSES, parseDoses(String.valueOf(doses)));
            }
            Object meds = health.get("medications");
            if (meds != null) editor.putString(KEY_MEDICATIONS, str(meds));
        }
        editor.apply();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
