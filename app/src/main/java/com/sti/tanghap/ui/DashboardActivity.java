package com.sti.tanghap.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.sti.tanghap.R;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.SessionEntity;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class DashboardActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        com.sti.tanghap.sync.SyncScheduler.schedulePeriodicSync(this);
        new com.sti.tanghap.data.SessionRepository(this).syncUserSessionsFromFirestore();

        TextView tvUser = findViewById(R.id.tvUser);
        TextView tvInitial = findViewById(R.id.tvUserInitial);

        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
            syncHealthProfileFromFirestore(uid);

            String email = FirebaseAuth.getInstance().getCurrentUser().getEmail();
            if (email != null) {
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
        findViewById(R.id.nav_report_container).setOnClickListener(v -> {
            Executors.newSingleThreadExecutor().execute(() -> {
                // Get start of today in milliseconds
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                long todayStart = cal.getTimeInMillis();

                // Fetch latest session from today for current user only
                String uid = FirebaseAuth.getInstance().getCurrentUser() != null ?
                        FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
                List<SessionEntity> sessions = AppDatabase.getInstance(this).sessionDao().getSinceForUser(uid, todayStart);
                
                Intent intent = new Intent(this, SessionResultActivity.class);
                if (sessions != null && !sessions.isEmpty()) {
                    // Pass the latest session of today
                    intent.putExtra("sessionId", sessions.get(sessions.size() - 1).id);
                }
                // If empty, SessionResultActivity will handle the "No Data" state
                runOnUiThread(() -> startActivity(intent));
            });
        });

        updateBatteryDisplay();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBatteryDisplay();
    }

    private void updateBatteryDisplay() {
        TextView tvDashboardBattery = findViewById(R.id.tvDashboardBattery);
        android.widget.ImageView ivDashboardBattery = findViewById(R.id.ivDashboardBattery);

        if (tvDashboardBattery != null) {
            SharedPreferences pref = getSharedPreferences("TanghapSessionHistory", MODE_PRIVATE);
            int lastBattery = pref.getInt("last_battery", -1);
            if (lastBattery != -1) {
                tvDashboardBattery.setText(lastBattery + "%");
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
                tvDashboardBattery.setText("--%");
                if (ivDashboardBattery != null) {
                    ivDashboardBattery.setImageResource(R.drawable.ic_battery_full);
                }
            }
        }
    }

    private void showProfileMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenu().add(0, 1, 0, "Settings");
        popup.getMenu().add(0, 2, 0, "Logout");

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    startActivity(new Intent(this, SettingsActivity.class));
                    return true;
                case 2:
                    FirebaseAuth.getInstance().signOut();
                    startActivity(new Intent(this, LoginActivity.class));
                    finish();
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    @SuppressWarnings("unchecked")
    private void syncHealthProfileFromFirestore(String uid) {
        com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        java.util.Map<String, Object> healthMap = (java.util.Map<String, Object>) doc.get("healthProfile");
                        if (healthMap != null) {
                            java.util.List<String> conditions = (java.util.List<String>) healthMap.get("diagnosedConditions");
                            SharedPreferences pref = getSharedPreferences("TanghapUserPreferences", MODE_PRIVATE);
                            SharedPreferences.Editor editor = pref.edit();
                            if (conditions != null) {
                                editor.putBoolean("has_asthma", conditions.contains("Asthma"));
                                editor.putBoolean("has_copd", conditions.contains("COPD"));
                                editor.putBoolean("has_bronchitis", conditions.contains("Bronchitis"));
                                editor.putBoolean("has_congestion", conditions.contains("Allergies") || conditions.contains("Mild Congestion"));
                            }
                            String usage = (String) healthMap.get("inhalerUsageFreqPerDay");
                            if (usage != null) editor.putString("inhaler_usage", usage);
                            editor.apply();
                        }
                    }
                });
    }
}