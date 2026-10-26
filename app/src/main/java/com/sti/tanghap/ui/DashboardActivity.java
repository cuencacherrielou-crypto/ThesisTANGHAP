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
import com.google.firebase.auth.FirebaseAuth;
import com.sti.tanghap.R;
import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.ai.BreathingAnalyzer;
import com.sti.tanghap.ai.CareAction;
import com.sti.tanghap.ai.SavedAssessment;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.HealthPrefs;
import com.sti.tanghap.data.ProfilePrefs;
import com.sti.tanghap.data.SessionDao;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.domain.BatteryAlert;
import com.sti.tanghap.domain.CoughHistory;
import com.sti.tanghap.domain.DailySummary;
import com.sti.tanghap.domain.DoseReminder;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DashboardActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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
        loadSummaries();
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

    /**
     * Battery icon only (full, half, low, or a question mark until the device has reported its
     * level), with a reminder to charge it when low. The notification is posted by MonitoringSession.
     */
    private void updateBatteryDisplay() {
        android.widget.ImageView ivDashboardBattery = findViewById(R.id.ivDashboardBattery);
        TextView tvBatteryHint = findViewById(R.id.tvBatteryHint);

        int lastBattery = getSharedPreferences(HealthPrefs.SESSION_PREFS, MODE_PRIVATE).getInt("last_battery", -1);
        if (lastBattery < 0) {
            ivDashboardBattery.setImageResource(R.drawable.ic_battery_unknown);
            ivDashboardBattery.setContentDescription(getString(R.string.battery_unknown));
            tvBatteryHint.setVisibility(View.GONE);
            return;
        }
        int description;
        if (lastBattery > 60) {
            ivDashboardBattery.setImageResource(R.drawable.ic_battery_full);
            description = R.string.battery_desc_full;
        } else if (lastBattery > BatteryAlert.LOW_PERCENT) {
            ivDashboardBattery.setImageResource(R.drawable.ic_battery_half);
            description = R.string.battery_desc_half;
        } else {
            ivDashboardBattery.setImageResource(R.drawable.ic_battery_low);
            description = R.string.battery_desc_low;
        }
        ivDashboardBattery.setContentDescription(getString(description));

        int level = BatteryAlert.levelFor(lastBattery);
        tvBatteryHint.setVisibility(level == BatteryAlert.LEVEL_NONE ? View.GONE : View.VISIBLE);
        tvBatteryHint.setText(level == BatteryAlert.LEVEL_CRITICAL
                ? R.string.battery_hint_critical : R.string.battery_hint_low);
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

    /** Today, the latest assessment and the last 7 days, from the sessions saved on this phone. */
    private void loadSummaries() {
        String uid = currentUid();
        int prescribed = ProfilePrefs.prescribedDoses(this);
        executor.execute(() -> {
            SessionDao dao = AppDatabase.getInstance(this).sessionDao();
            long now = System.currentTimeMillis();
            List<SessionEntity> recent = dao.getSinceForUser(uid, now - CoughHistory.LOOKBACK_MS);
            List<DailySummary.Day> days = DailySummary.build(recent, Calendar.getInstance(), 14);
            CoughHistory.Result coughs = CoughHistory.evaluate(
                    CoughHistory.coughTimesBefore(recent, now + 1), now, ZoneId.systemDefault());
            SessionEntity latest = !recent.isEmpty() ? recent.get(recent.size() - 1)
                    : dao.getAllForUser(uid).stream().findFirst().orElse(null);
            String condition = latest == null ? null
                    : SavedAssessment.of(latest, HealthPrefs.loadProfile(this)).possibleCondition;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                showToday(days.get(0), prescribed);
                showLatest(latest, condition);
                showWeek(days, coughs);
            });
        });
    }

    private void showToday(DailySummary.Day today, int prescribed) {
        TextView doses = findViewById(R.id.tvTodayDoses);
        doses.setText(prescribed > 0 ? today.inhalerPresses + " / " + prescribed : String.valueOf(today.inhalerPresses));
        ((TextView) findViewById(R.id.tvTodayCoughs)).setText(String.valueOf(today.coughs));
        ((TextView) findViewById(R.id.tvTodayWheezes)).setText(String.valueOf(today.wheezes));
        ((TextView) findViewById(R.id.tvTodayMonitored)).setText(formatDuration(today.monitoredMillis));

        TextView status = findViewById(R.id.tvDoseStatus);
        int color;
        if (prescribed <= 0) {
            status.setText(R.string.dose_status_not_set);
            color = R.color.text_medium_emphasis;
        } else {
            Calendar cal = Calendar.getInstance();
            int minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            DoseReminder.Result r = new DoseReminder().evaluate(prescribed, today.inhalerPresses, minute);
            if (r.status == DoseReminder.Status.MISSED) {
                status.setText(r.title + ": " + r.message);
                color = R.color.error;
            } else if (r.status == DoseReminder.Status.LATE) {
                status.setText(r.title + ": " + r.message);
                color = R.color.warning;
            } else if (today.inhalerPresses >= prescribed) {
                status.setText(R.string.dose_status_all_taken);
                color = R.color.success;
            } else {
                status.setText(R.string.dose_status_on_track);
                color = R.color.success;
            }
        }
        status.setTextColor(ContextCompat.getColor(this, color));
    }

    private void showLatest(SessionEntity latest, String condition) {
        View card = findViewById(R.id.cardLatest);
        TextView when = findViewById(R.id.tvLatestWhen);
        TextView action = findViewById(R.id.tvLatestAction);
        TextView detail = findViewById(R.id.tvLatestCondition);
        View open = findViewById(R.id.tvLatestOpen);
        if (latest == null) {
            when.setText("");
            action.setText(R.string.sensor_no_session);
            action.setTextColor(ContextCompat.getColor(this, R.color.text_high_emphasis));
            detail.setText(R.string.latest_none_hint);
            open.setVisibility(View.GONE);
            card.setOnClickListener(v -> startActivity(new Intent(this, LiveSessionActivity.class)));
            return;
        }
        CareAction care = CareAction.of(latest.riskLevelHigh, latest.recommendation, condition);
        when.setText(new SimpleDateFormat("MMM d, hh:mm a", Locale.getDefault()).format(new Date(latest.startTimeMillis)));
        action.setText(care.headline);
        action.setTextColor(ContextCompat.getColor(this, care == CareAction.HOSPITAL ? R.color.error
                : care == CareAction.CLINIC ? R.color.warning : R.color.success));
        detail.setText(condition != null ? condition : AssessmentEngine.CATEGORY_STANDARD);
        open.setVisibility(View.VISIBLE);
        card.setOnClickListener(v -> startActivity(new Intent(this, SessionResultActivity.class)
                .putExtra("sessionId", latest.id)));
    }

    /** @param days newest first, 14 days: the last 7 and the 7 before them */
    private void showWeek(List<DailySummary.Day> days, CoughHistory.Result history) {
        int coughs = 0, previous = 0, coughDays = 0, bpmSum = 0, bpmDays = 0, monitoredDays = 0;
        for (int i = 0; i < days.size(); i++) {
            DailySummary.Day d = days.get(i);
            if (i >= 7) {
                previous += d.coughs;
                continue;
            }
            coughs += d.coughs;
            if (d.coughs > 0) coughDays++;
            if (d.hasData()) monitoredDays++;
            if (d.averageBpm() > 0) {
                bpmSum += d.averageBpm();
                bpmDays++;
            }
        }
        ((TextView) findViewById(R.id.tvWeekCoughs)).setText(String.valueOf(coughs));
        ((TextView) findViewById(R.id.tvWeekCoughDays)).setText(coughDays + " / 7");
        // The week's breathing as a pattern only (the estimated rate itself is not shown)
        ((TextView) findViewById(R.id.tvWeekBreathing)).setText(bpmDays > 0
                ? BreathingAnalyzer.shortLabel(BreathingAnalyzer.classify(Math.round(bpmSum / (float) bpmDays),
                        HealthPrefs.loadProfile(this).ageYears).pattern)
                : "--");

        TextView note = findViewById(R.id.tvWeekNote);
        int color = R.color.text_medium_emphasis;
        if (history.persistent) {
            note.setText(getString(R.string.week_note_persistent, history.spellDays));
            color = R.color.warning;
        } else if (monitoredDays == 0) {
            note.setText(R.string.week_note_no_data);
        } else if (coughs > previous) {
            note.setText(getString(R.string.week_note_more, coughs - previous));
            color = R.color.warning;
        } else if (coughs < previous) {
            note.setText(getString(R.string.week_note_fewer, previous - coughs));
            color = R.color.success;
        } else {
            note.setText(R.string.week_note_same);
        }
        note.setTextColor(ContextCompat.getColor(this, color));
    }

    private static String formatDuration(long millis) {
        long minutes = millis / 60000;
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " m" : minutes + " min";
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
