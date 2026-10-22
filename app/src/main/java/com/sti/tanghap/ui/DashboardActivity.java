package com.sti.tanghap.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.firebase.auth.FirebaseAuth;
import com.sti.tanghap.R;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.HealthPrefs;
import com.sti.tanghap.data.SessionEntity;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DashboardActivity extends AppCompatActivity {
    private static final int TREND_SESSIONS = 14;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LineChart chartDashboard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        com.sti.tanghap.sync.SyncScheduler.schedulePeriodicSync(this);
        com.sti.tanghap.notify.NotificationHelper.ensureChannel(this);
        com.sti.tanghap.notify.DoseReminderWorker.schedule(this);
        requestNotificationPermissionIfNeeded();
        new com.sti.tanghap.data.SessionRepository(this).syncUserSessionsFromFirestore();

        TextView tvUser = findViewById(R.id.tvUser);
        TextView tvInitial = findViewById(R.id.tvUserInitial);

        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
            syncHealthProfileFromFirestore(uid);

            String email = FirebaseAuth.getInstance().getCurrentUser().getEmail();
            if (email != null && !email.isEmpty()) {
                tvUser.setText(email.split("@")[0]);
                tvInitial.setText(email.substring(0, 1).toUpperCase(Locale.getDefault()));
            }
        }

        chartDashboard = findViewById(R.id.chartDashboard);
        styleChart();

        // Profile Click (Settings/Logout)
        findViewById(R.id.btnProfile).setOnClickListener(this::showProfileMenu);

        // Custom High-Tech Dock Navigation
        findViewById(R.id.btnLive).setOnClickListener(v ->
                startActivity(new Intent(this, LiveSessionActivity.class)));

        findViewById(R.id.nav_history_container).setOnClickListener(v ->
                startActivity(new Intent(this, HistoryTrendsActivity.class)));

        findViewById(R.id.nav_alerts_container).setOnClickListener(v ->
                startActivity(new Intent(this, AlertsActivity.class)));

        findViewById(R.id.nav_scan_container).setOnClickListener(v ->
                startActivity(new Intent(this, DeviceScanActivity.class)));

        // Navigation to Session Result (Monitoring Report) - Refreshes Everyday
        findViewById(R.id.nav_report_container).setOnClickListener(v -> executor.execute(() -> {
            // Get start of today in milliseconds
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long todayStart = cal.getTimeInMillis();

            // Fetch latest session from today for current user only
            String uid = currentUid();
            List<SessionEntity> sessions = AppDatabase.getInstance(this).sessionDao().getSinceForUser(uid, todayStart);

            if (sessions == null || sessions.isEmpty()) {
                runOnUiThread(() -> android.widget.Toast.makeText(this,
                        "No monitoring session today yet. Tap the center button to start one.",
                        android.widget.Toast.LENGTH_LONG).show());
                return;
            }
            // Open the latest session of today
            Intent intent = new Intent(this, SessionResultActivity.class);
            intent.putExtra("sessionId", sessions.get(sessions.size() - 1).id);
            runOnUiThread(() -> startActivity(intent));
        }));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBatteryDisplay();
        updateStatusCard();
        loadPressureTrend();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private static String currentUid() {
        return FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
    }

    private void updateBatteryDisplay() {
        TextView tvDashboardBattery = findViewById(R.id.tvDashboardBattery);
        android.widget.ImageView ivDashboardBattery = findViewById(R.id.ivDashboardBattery);

        if (tvDashboardBattery != null) {
            SharedPreferences pref = getSharedPreferences(HealthPrefs.SESSION_PREFS, MODE_PRIVATE);
            int lastBattery = pref.getInt("last_battery", -1);
            if (lastBattery != -1) {
                tvDashboardBattery.setText(getString(R.string.battery_percent, lastBattery));
                if (ivDashboardBattery != null) {
                    if (lastBattery > 60) {
                        ivDashboardBattery.setImageResource(R.drawable.ic_battery_full);
                    } else if (lastBattery > 20) {
                        ivDashboardBattery.setImageResource(R.drawable.ic_battery_half);
                    } else {
                        ivDashboardBattery.setImageResource(R.drawable.ic_battery_low);
                    }
                }
            } else {
                tvDashboardBattery.setText(R.string.battery_unknown);
                if (ivDashboardBattery != null) {
                    ivDashboardBattery.setImageResource(R.drawable.ic_battery_full);
                }
            }
        }
    }

    /** Status pill and sensor line reflect real data instead of fixed text. */
    private void updateStatusCard() {
        TextView statusPill = findViewById(R.id.statusPill);
        TextView tvSensorStatus = findViewById(R.id.tvSensorStatus);

        boolean paired = getSharedPreferences("app_prefs", MODE_PRIVATE)
                .getString(LiveSessionActivity.PREF_LAST_DEVICE, null) != null;
        statusPill.setText(paired ? R.string.ready_caps : R.string.not_paired_caps);

        // A session running in the background: the center button returns to it
        if (com.sti.tanghap.monitor.MonitoringService.activeSession() != null) {
            statusPill.setText(R.string.monitoring_caps);
            tvSensorStatus.setText(R.string.sensor_monitoring_background);
            return;
        }

        long lastSession = getSharedPreferences(HealthPrefs.SESSION_PREFS, MODE_PRIVATE).getLong("last_timestamp", 0);
        if (lastSession > 0) {
            CharSequence when = DateUtils.getRelativeTimeSpanString(
                    lastSession, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            tvSensorStatus.setText(getString(R.string.sensor_last_session, when));
        } else {
            tvSensorStatus.setText(R.string.sensor_no_session);
        }
    }

    private void styleChart() {
        int textColor = ContextCompat.getColor(this, R.color.text_medium_emphasis);
        chartDashboard.getDescription().setEnabled(false);
        chartDashboard.getLegend().setEnabled(false);
        chartDashboard.getAxisRight().setEnabled(false);
        chartDashboard.setTouchEnabled(false);
        chartDashboard.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        chartDashboard.getXAxis().setDrawGridLines(false);
        chartDashboard.getXAxis().setDrawLabels(false);
        chartDashboard.getAxisLeft().setTextColor(textColor);
        chartDashboard.getAxisLeft().setGridColor(ContextCompat.getColor(this, R.color.divider));
        chartDashboard.getAxisLeft().setAxisMinimum(0f);
        chartDashboard.setNoDataText(getString(R.string.chart_no_sessions));
        chartDashboard.setNoDataTextColor(textColor);
    }

    /** Peak inhalation pressure of the user's most recent sessions, oldest to newest. */
    private void loadPressureTrend() {
        String uid = currentUid();
        executor.execute(() -> {
            List<SessionEntity> newestFirst = AppDatabase.getInstance(this).sessionDao().getAllForUser(uid);
            // Only sessions that recorded pressure; 0 means "not measured", not a weak inhalation
            List<SessionEntity> measured = new ArrayList<>();
            for (SessionEntity s : newestFirst) {
                if (s.peakPressureKpa > 0) measured.add(s);
                if (measured.size() == TREND_SESSIONS) break;
            }
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < measured.size(); i++) {
                entries.add(new Entry(i, measured.get(measured.size() - 1 - i).peakPressureKpa));
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (entries.isEmpty()) {
                    chartDashboard.clear();
                    return;
                }
                int color = ContextCompat.getColor(this, R.color.accent_teal);
                LineDataSet set = new LineDataSet(entries, "Peak Pressure");
                set.setColor(color);
                set.setCircleColor(color);
                set.setLineWidth(2.5f);
                set.setDrawValues(false);
                set.setMode(LineDataSet.Mode.CUBIC_BEZIER);
                chartDashboard.setData(new LineData(set));
                chartDashboard.invalidate();
            });
        });
    }

    private void showProfileMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenu().add(0, 1, 0, R.string.menu_settings);
        popup.getMenu().add(0, 2, 0, R.string.menu_logout);

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    startActivity(new Intent(this, SettingsActivity.class));
                    return true;
                case 2:
                    // A session still running is saved for this account before signing out
                    if (com.sti.tanghap.monitor.MonitoringService.activeSession() != null) {
                        com.sti.tanghap.monitor.MonitoringService.activeSession().endAndSave();
                    }
                    FirebaseAuth.getInstance().signOut();
                    HealthPrefs.clearUserData(this);
                    Intent login = new Intent(this, LoginActivity.class);
                    login.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(login);
                    finish();
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    private static final int REQ_NOTIFICATIONS = 4101;

    /** Android 13+ needs runtime permission to show alerts as phone notifications. */
    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                && androidx.core.content.ContextCompat.checkSelfPermission(this,
                        android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    @SuppressWarnings("unchecked")
    private void syncHealthProfileFromFirestore(String uid) {
        com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        com.sti.tanghap.data.ProfilePrefs.syncFromUserDoc(this, doc.getData());
                        HealthPrefs.saveBirthdayAndAge(this, doc.get("birthday"), doc.get("age"));
                        Object healthMap = doc.get("healthProfile");
                        if (healthMap instanceof Map) {
                            HealthPrefs.saveFromHealthMap(this, (Map<String, Object>) healthMap);
                        }
                    }
                });
    }
}
