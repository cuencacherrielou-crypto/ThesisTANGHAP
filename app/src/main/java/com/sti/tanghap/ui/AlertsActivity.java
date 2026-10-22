package com.sti.tanghap.ui;

import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.android.material.button.MaterialButton;
import com.sti.tanghap.R;
import com.sti.tanghap.ai.SavedAssessment;
import com.sti.tanghap.data.ConnectionLog;
import com.sti.tanghap.data.HealthPrefs;
import com.sti.tanghap.domain.AgeCalculator;
import com.sti.tanghap.domain.DoseReminder;
import com.sti.tanghap.notify.DoseCheck;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.domain.AlertEngine;
import com.sti.tanghap.domain.BaselineCalculator;
import com.sti.tanghap.ui.adapter.AlertAdapter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AlertsActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private RecyclerView rvAlerts;
    private MaterialButton btnCallEmergency;
    private MaterialButton btnNearbyHospitals;
    private FirebaseFirestore db;
    private FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alerts);

        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        rvAlerts = findViewById(R.id.rvAlerts);
        rvAlerts.setLayoutManager(new LinearLayoutManager(this));
        btnCallEmergency = findViewById(R.id.btnCallEmergency);
        btnNearbyHospitals = findViewById(R.id.btnNearbyHospitals);
        btnNearbyHospitals.setOnClickListener(v -> NearbyHospitals.open(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload so a session saved or reviewed meanwhile is reflected
        loadUserAndAlerts();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void loadUserAndAlerts() {
        if (auth.getCurrentUser() == null) return;

        db.collection("users").document(auth.getCurrentUser().getUid()).get()
                .addOnSuccessListener(doc -> {
                    // Age from the birthday (stays correct as the user gets older); unknown = adult range
                    int age = doc.exists()
                            ? AgeCalculator.resolve(doc.get("birthday"), doc.get("age"), Calendar.getInstance())
                            : AgeCalculator.UNKNOWN;
                    loadAlerts(age);
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to load profile for age-based analysis.", Toast.LENGTH_SHORT).show();
                    // Offline: use the age cached on this phone
                    loadAlerts(HealthPrefs.loadProfile(this).ageYears);
                });
    }

    private void loadAlerts(int userAge) {
        // The profile lookup can answer after this screen closed
        if (auth.getCurrentUser() == null || executor.isShutdown()) return;
        String uid = auth.getCurrentUser().getUid();
        executor.execute(() -> {
            long now = System.currentTimeMillis();

            // This user's sessions only, newest first
            List<SessionEntity> allSessions = AppDatabase.getInstance(this).sessionDao().getAllForUser(uid);
            SessionEntity latest = allSessions.isEmpty() ? null : allSessions.get(0);

            // Baseline = sessions in the 7 days before the latest one, excluding the latest itself
            ArrayList<SessionEntity> baselineList = new ArrayList<>();
            if (latest != null) {
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(latest.startTimeMillis);
                cal.add(Calendar.DAY_OF_YEAR, -7);
                long since = cal.getTimeInMillis();
                for (SessionEntity s : allSessions) {
                    if (s != latest && s.startTimeMillis >= since && s.startTimeMillis < latest.startTimeMillis) {
                        baselineList.add(s);
                    }
                }
            }

            BaselineCalculator.Baseline baseline = new BaselineCalculator().compute(baselineList);

            float todayPeak = 0;
            float todayInhale = 0;
            int todayCough = 0;
            int todayAct = 0;
            int todayBpm = 0;

            if (latest != null) {
                todayPeak = latest.peakPressureKpa;
                todayInhale = latest.inhaleDurationMillis;
                todayCough = latest.coughCount;
                todayAct = latest.actuationCount;
                todayBpm = latest.averageBpm;
            }

            List<AlertEngine.Alert> alerts = new ArrayList<>();

            if (com.sti.tanghap.monitor.MonitoringService.activeSession() != null) {
                alerts.add(new AlertEngine.Alert(
                        "Monitoring in progress",
                        "A session is still recording. Its results appear here after you end and save it.",
                        "INFO"
                ));
            }

            // Worsening finding from the most recent session
            if (latest != null && latest.riskLevelHigh) {
                String condition = SavedAssessment.of(latest, null).possibleCondition;
                alerts.add(new AlertEngine.Alert(
                        "Worsening respiratory pattern",
                        (condition != null ? condition + ": " : "")
                                + "your last session suggests a possible worsening condition. Contact your emergency contact or visit the nearest hospital if symptoms continue. This is not a diagnosis.",
                        "WARN"
                ));
            }

            alerts.addAll(new AlertEngine().evaluate(
                    baseline, userAge, todayBpm, todayPeak, todayInhale, todayCough, todayAct
            ));

            // Late or missed inhaler doses
            DoseReminder.Result dose = DoseCheck.checkToday(this);
            if (dose.status != DoseReminder.Status.NONE) {
                alerts.add(new AlertEngine.Alert(dose.title, dose.message,
                        dose.status == DoseReminder.Status.MISSED ? "WARN" : "INFO"));
            }

            // Device connection issues in the last 24 hours
            List<Long> drops = ConnectionLog.since(this, now - 24L * 60 * 60 * 1000);
            if (!drops.isEmpty()) {
                SimpleDateFormat timeFmt = new SimpleDateFormat("hh:mm a", Locale.getDefault());
                alerts.add(new AlertEngine.Alert(
                        "Device connection issue",
                        "The TANGHAP device lost its Bluetooth connection " + drops.size()
                                + (drops.size() == 1 ? " time" : " times") + " in the last 24 hours (latest at "
                                + timeFmt.format(new Date(drops.get(0))) + "). Keep your phone within range and check the device battery.",
                        "INFO"
                ));
            }

            boolean hasWarning = false;
            for (AlertEngine.Alert a : alerts) {
                if ("WARN".equals(a.severity)) { hasWarning = true; break; }
            }
            final boolean showEmergency = hasWarning;
            final boolean showHospitals = latest != null && latest.riskLevelHigh;

            if (alerts.isEmpty()) {
                alerts.add(latest == null
                        ? new AlertEngine.Alert("No sessions yet",
                                "Complete a monitoring session to see alerts about your breathing, coughs and inhaler use.",
                                "INFO")
                        : new AlertEngine.Alert("No Alerts",
                                "Your latest session is within your usual range. Continue regular monitoring.",
                                "INFO"));
            }

            List<AlertEngine.Alert> finalAlerts = alerts;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                rvAlerts.setAdapter(new AlertAdapter(finalAlerts));
                EmergencyContactButton.bind(this, btnCallEmergency, showEmergency);
                btnNearbyHospitals.setVisibility(showHospitals ? android.view.View.VISIBLE : android.view.View.GONE);
            });
        });
    }
}
