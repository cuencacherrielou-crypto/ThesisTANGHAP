package com.sti.tanghap.monitor;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.sti.tanghap.R;
import com.sti.tanghap.notify.NotificationHelper;
import com.sti.tanghap.ui.LiveSessionActivity;
import com.sti.tanghap.ui.SessionResultActivity;

/**
 * Foreground service that runs a {@link MonitoringSession}, so monitoring continues while the
 * user is in another app or the phone is locked. Android shows its ongoing notification
 * ("TANGHAP is monitoring") for as long as it runs; the notification also has End & Save.
 */
public class MonitoringService extends Service implements MonitoringSession.Host {
    private static final String TAG = "MonitoringService";

    public static final String ACTION_START = "com.sti.tanghap.monitor.START";
    public static final String ACTION_END_AND_SAVE = "com.sti.tanghap.monitor.END_AND_SAVE";
    public static final String EXTRA_DEVICE_ADDRESS = "deviceAddress";

    private static final String CHANNEL_MONITORING = "tanghap_monitoring";
    private static final int ID_ONGOING = 2101;
    private static final int ID_SESSION_DONE = 2102;
    /** The ongoing notification is refreshed at most this often. */
    private static final long NOTIFICATION_REFRESH_MS = 5_000;

    /** The running session, or null. Read and written on the main thread only. */
    private static MonitoringSession active;

    private long lastNotificationMillis = 0;

    public static MonitoringSession activeSession() {
        return active;
    }

    /** Starts monitoring the given device (call from a visible screen). */
    public static void start(Context ctx, String deviceAddress) {
        Intent i = new Intent(ctx, MonitoringService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress);
        ContextCompat.startForegroundService(ctx, i);
    }

    /** Binding gives the live screen direct access to the running session. */
    public class LocalBinder extends Binder {
        public MonitoringSession session() {
            return active;
        }
    }

    private final IBinder binder = new LocalBinder();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_END_AND_SAVE.equals(action)) {
            if (active != null) {
                active.endAndSave();
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        // ACTION_START. Every start must call startForeground, even when nothing new starts,
        // or Android stops the app.
        if (!goForeground(buildOngoing(active))) {
            if (active == null) stopSelf();
            return START_NOT_STICKY;
        }
        if (active != null) return START_NOT_STICKY; // already monitoring
        String address = intent != null ? intent.getStringExtra(EXTRA_DEVICE_ADDRESS) : null;
        if (address == null || address.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        active = new MonitoringSession(this, this, address);
        active.start();
        // Not restarted automatically after the system kills it: a restarted service would
        // begin a new, empty session instead of continuing the old one
        return START_NOT_STICKY;
    }

    private boolean goForeground(Notification n) {
        try {
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : 0;
            ServiceCompat.startForeground(this, ID_ONGOING, n, type);
            return true;
        } catch (RuntimeException e) {
            // e.g. Bluetooth permission missing, or the app was not visible when starting
            Log.e(TAG, "Could not start background monitoring", e);
            return false;
        }
    }

    // =========================================================
    // SESSION CALLBACKS (main thread)
    // =========================================================

    @Override
    public void onSessionChanged(MonitoringSession session) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotificationMillis < NOTIFICATION_REFRESH_MS) return;
        lastNotificationMillis = now;
        notifyOngoing(buildOngoing(session));
    }

    @Override
    public void onSessionFinished(MonitoringSession session, String sessionId, String message) {
        if (active == session) active = null;
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);

        // Tell the user even when the app is closed
        if (!LiveSessionActivity.isVisible()) {
            postSessionDone(sessionId, message);
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // The service is only destroyed early if the user force-stops the app; save what we have
        if (active != null) active.endAndSave();
    }

    // =========================================================
    // NOTIFICATIONS
    // =========================================================

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_MONITORING,
                    "Monitoring in progress", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while TANGHAP is recording a monitoring session");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildOngoing(MonitoringSession s) {
        ensureChannel();
        Intent open = new Intent(this, LiveSessionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent end = new Intent(this, MonitoringService.class).setAction(ACTION_END_AND_SAVE);
        PendingIntent endPi = PendingIntent.getService(this, 1, end,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text;
        if (s == null) {
            text = "Connecting to your TANGHAP device...";
        } else {
            long minutes = (System.currentTimeMillis() - s.getStartMillis()) / 60_000;
            text = (s.isConnected() ? "Recording" : "Waiting for the device")
                    + " • " + minutes + " min • " + s.getCountedCoughs()
                    + (s.getCountedCoughs() == 1 ? " cough • " : " coughs • ")
                    + s.getActuations() + (s.getActuations() == 1 ? " inhaler press" : " inhaler presses");
        }
        return new NotificationCompat.Builder(this, CHANNEL_MONITORING)
                .setSmallIcon(R.drawable.ic_nav_live)
                .setContentTitle("TANGHAP is monitoring")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setUsesChronometer(s != null)
                .setWhen(s != null ? s.getStartMillis() : System.currentTimeMillis())
                .setContentIntent(openPi)
                .addAction(0, "End & Save", endPi)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    @SuppressLint("MissingPermission")
    private void notifyOngoing(Notification n) {
        if (NotificationHelper.canNotify(this)) {
            NotificationManagerCompat.from(this).notify(ID_ONGOING, n);
        }
    }

    @SuppressLint("MissingPermission")
    private void postSessionDone(String sessionId, String message) {
        if (!NotificationHelper.canNotify(this)) return;
        NotificationHelper.ensureChannel(this);
        Intent open;
        if (sessionId != null) {
            open = new Intent(this, SessionResultActivity.class).putExtra("sessionId", sessionId);
        } else {
            open = new Intent(this, com.sti.tanghap.ui.DashboardActivity.class);
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = sessionId != null ? "Monitoring session saved" : "Monitoring session ended";
        String text = sessionId != null ? "Tap to see the results." : message;
        NotificationManagerCompat.from(this).notify(ID_SESSION_DONE,
                new NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ALERTS)
                        .setSmallIcon(R.drawable.ic_nav_live)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                        .setAutoCancel(true)
                        .setContentIntent(pi)
                        .build());
    }
}
