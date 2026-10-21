package com.sti.tanghap.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Device-level app settings chosen on the Settings screen (kept in "app_prefs",
 * so they survive logout, unlike the per-user data in HealthPrefs).
 */
public final class AppSettings {

    public static final String PREFS = "app_prefs";

    public static final String KEY_DARK_MODE = "dark_mode";
    public static final String KEY_ALERT_NOTIFICATIONS = "alert_notifications_enabled";
    public static final String KEY_DOSE_REMINDERS = "dose_reminders_enabled";

    private AppSettings() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Phone notifications for worsening readings and connection issues; on by default. */
    public static boolean alertNotificationsEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ALERT_NOTIFICATIONS, true);
    }

    /** Phone notifications for late or missed prescribed doses; on by default. */
    public static boolean doseRemindersEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_DOSE_REMINDERS, true);
    }
}
