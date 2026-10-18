package com.sti.tanghap.ui;

import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.sti.tanghap.R;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.domain.AlertEngine;
import com.sti.tanghap.domain.BaselineCalculator;
import com.sti.tanghap.ui.adapter.AlertAdapter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AlertsActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private RecyclerView rvAlerts;
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

        loadUserAndAlerts();
    }

    private void loadUserAndAlerts() {
        if (auth.getCurrentUser() == null) return;

        db.collection("users").document(auth.getCurrentUser().getUid()).get()
                .addOnSuccessListener(doc -> {
                    int age = 25; // Default if not found
                    if (doc.exists() && doc.get("age") != null) {
                        try {
                            String ageStr = String.valueOf(doc.get("age"));
                            age = Integer.parseInt(ageStr);
                        } catch (Exception e) {
                            age = 25;
                        }
                    }
                    final int userAge = age;
                    loadAlerts(userAge);
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to load profile for age-based analysis.", Toast.LENGTH_SHORT).show();
                    loadAlerts(25);
                });
    }

    private void loadAlerts(int userAge) {
        executor.execute(() -> {
            List<SessionEntity> allSessions = AppDatabase.getInstance(this).sessionDao().getAll();
            
            // Logic for baseline (last 7 days)
            long now = System.currentTimeMillis();
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(now);
            cal.add(Calendar.DAY_OF_YEAR, -7);
            long since = cal.getTimeInMillis();

            ArrayList<SessionEntity> baselineList = new ArrayList<>();
            for (SessionEntity s : allSessions) {
                if (s.startTimeMillis >= since) baselineList.add(s);
            }

            BaselineCalculator.Baseline baseline = new BaselineCalculator().compute(baselineList);

            float todayPeak = 0;
            float todayInhale = 0;
            int todayCough = 0;
            int todayAct = 0;
            int todayBpm = 0;

            if (!allSessions.isEmpty()) {
                SessionEntity latest = allSessions.get(0);
                todayPeak = latest.peakPressureKpa;
                todayInhale = latest.inhaleDurationMillis;
                todayCough = latest.coughCount;
                todayAct = latest.actuationCount;
                todayBpm = latest.averageBpm;
            }

            List<AlertEngine.Alert> alerts = new AlertEngine().evaluate(
                    baseline, userAge, todayBpm, todayPeak, todayInhale, todayCough, todayAct
            );

            if (alerts.isEmpty()) {
                alerts.add(new AlertEngine.Alert(
                        "No Alerts",
                        "Your trends are stable. Continue regular monitoring.",
                        "INFO"
                ));
            }

            List<AlertEngine.Alert> finalAlerts = alerts;
            runOnUiThread(() -> {
                rvAlerts.setAdapter(new AlertAdapter(finalAlerts));
            });
        });
    }
}
