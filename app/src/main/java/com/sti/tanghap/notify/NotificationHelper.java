package com.sti.tanghap.notify;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.sti.tanghap.R;
import com.sti.tanghap.data.AppSettings;
import com.sti.tanghap.ui.AlertsActivity;

/** Posts TANGHAP system notifications for worsening readings, missed doses and connection issues. */
public final class NotificationHelper {

    public static final String CHANNEL_ALERTS = "tanghap_alerts";

    public static final int ID_WORSENING = 2001;
    public static final int ID_CONNECTION = 2002;
    public static final int ID_DOSE = 2003;
    public static final int ID_BATTERY = 2004;

    private NotificationHelper() {}

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ALERTS,
                    "Health alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Worsening readings, missed inhaler doses, device connection issues and low device battery");
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    public static boolean canNotify(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled();
    }

    /** Whether the user left this kind of notification on in Settings. */
    public static boolean enabledInSettings(Context ctx, int id) {
        return id == ID_DOSE ? AppSettings.doseRemindersEnabled(ctx) : AppSettings.alertNotificationsEnabled(ctx);
    }

    /** Shows a notification that opens the Alerts screen. Does nothing without permission or when turned off in Settings. */
    public static void show(Context ctx, int id, String title, String text) {
        show(ctx, id, title, text, new Intent(ctx, AlertsActivity.class));
    }

    /**
     * Shows a notification that opens the given screen. Does nothing without permission or
     * when turned off in Settings.
     */
    @SuppressLint("MissingPermission")
    public static void show(Context ctx, int id, String title, String text, Intent open) {
        if (!enabledInSettings(ctx, id) || !canNotify(ctx)) return;
        ensureChannel(ctx);

        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_nav_alerts)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);

        NotificationManagerCompat.from(ctx).notify(id, builder.build());
    }
}
