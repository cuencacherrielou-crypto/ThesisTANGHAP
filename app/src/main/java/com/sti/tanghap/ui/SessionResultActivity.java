package com.sti.tanghap.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.sti.tanghap.R;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.domain.BaselineCalculator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SessionResultActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // STANDARD 12-HOUR TIME FORMAT WITH AM/PM DISPLAY
    private final SimpleDateFormat sdf = new SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm a", Locale.getDefault());
    private final BaselineCalculator baselineCalculator = new BaselineCalculator();

    private TextView tvAvgBpm, tvPeak, tvInhaleDur, tvCough, tvAct, tvDate;
    private TextView tvAiFindings, tvAiObservations, tvOtcRecommendation, tvBaselineComparison;
    private View layoutOtc;
    private MaterialButton btnNearbyHospitals;
    private MaterialButton btnCallEmergency;
    private LineChart chartResultPattern;
    private String currentSessionId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_result);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        // Standard Metrics
        tvAvgBpm = findViewById(R.id.tvAvgBpm);
        tvPeak = findViewById(R.id.tvPeak);
        tvInhaleDur = findViewById(R.id.tvInhaleDur);
        tvCough = findViewById(R.id.tvCough);
        tvAct = findViewById(R.id.tvAct);
        tvDate = findViewById(R.id.tvDate);

        // AI Integrated UI
        tvAiFindings = findViewById(R.id.tvAiFindings);
        tvAiObservations = findViewById(R.id.tvAiObservations);
        tvOtcRecommendation = findViewById(R.id.tvOtcRecommendation);
        tvBaselineComparison = findViewById(R.id.tvBaselineComparison);
        layoutOtc = findViewById(R.id.layoutOtc);
        btnNearbyHospitals = findViewById(R.id.btnNearbyHospitals);
        btnCallEmergency = findViewById(R.id.btnCallEmergency);

        chartResultPattern = findViewById(R.id.chartResultPattern);

        setupChart();

        currentSessionId = getIntent().getStringExtra("sessionId");

        findViewById(R.id.btnGoDashboard).setOnClickListener(v -> finish());
        btnNearbyHospitals.setOnClickListener(v -> findNearbyHospitals());

        findViewById(R.id.btnExport).setOnClickListener(v -> {
            if (currentSessionId != null) {
                Intent i = new Intent(this, DoctorExportActivity.class);
                i.putExtra("sessionId", currentSessionId);
                startActivity(i);
            } else {
                Toast.makeText(this, "Session data not ready for export.", Toast.LENGTH_SHORT).show();
            }
        });

        if (currentSessionId != null) {
            // Load existing session from database (e.g. opened from History)
            loadSessionData(currentSessionId);
        } else {
            // Processing Live Session Results passed from LiveSessionActivity
            processAndSaveLiveSession();
        }
    }

    private void setupChart() {
        chartResultPattern.getDescription().setEnabled(false);
        chartResultPattern.getLegend().setEnabled(false);
        chartResultPattern.getAxisRight().setEnabled(false);
        chartResultPattern.getXAxis().setEnabled(false);
        chartResultPattern.getAxisLeft().setDrawGridLines(false);
        chartResultPattern.getAxisLeft().setDrawLabels(false);
        chartResultPattern.setTouchEnabled(false);
    }

    private void processAndSaveLiveSession() {
        float maxEffort = getIntent().getFloatExtra("maxEffort", 0f);
        String breathingRateStr = getIntent().getStringExtra("breathingRate");
        String breathingPattern = getIntent().getStringExtra("breathingPattern");
        String aiFindings = getIntent().getStringExtra("aiFindings");
        String aiObservations = getIntent().getStringExtra("aiObservations");

        // EXTRACT SENSOR METRICS FROM INTENT EXTRAS
        int actuationCount = getIntent().getIntExtra("actuationCount", 0);
        int coughCount = getIntent().getIntExtra("coughCount", 0);
        long startMillis = getIntent().getLongExtra("startTimeMillis", System.currentTimeMillis());
        long endMillis = getIntent().getLongExtra("endTimeMillis", System.currentTimeMillis());
        long inhaleMillis = getIntent().getLongExtra("inhaleDurationMillis", 0L);
        boolean riskHigh = getIntent().getBooleanExtra("riskLevelHigh", false);
        String otcRecommendation = getIntent().getStringExtra("otcRecommendation");

        if (breathingRateStr != null || aiFindings != null) {
            int bpmVal = 0;
            if (breathingRateStr != null) {
                try {
                    bpmVal = Integer.parseInt(breathingRateStr.replaceAll("[^0-9]", ""));
                } catch (Exception ignored) {}
            }

            currentSessionId = UUID.randomUUID().toString();
            SessionEntity newSession = new SessionEntity(currentSessionId);

            if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
                newSession.userId = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid();
            }

            newSession.startTimeMillis = startMillis;
            newSession.endTimeMillis = endMillis;
            newSession.inhaleDurationMillis = inhaleMillis;
            newSession.riskLevelHigh = riskHigh;
            newSession.otcRecommendation = otcRecommendation;
            newSession.peakPressureKpa = maxEffort;
            newSession.averageBpm = bpmVal;
            newSession.detectedPattern = breathingPattern != null ? breathingPattern : "Normal";
            newSession.aiPreliminaryInsight = aiFindings != null ? aiFindings : "Session analysis complete.";
            newSession.aiObservations = aiObservations != null ? aiObservations : "Respiratory pattern monitored.";

            // BIND ACTUATION AND COUGH VALUES TO DATABASE ENTITY
            newSession.actuationCount = actuationCount;
            newSession.coughCount = coughCount;

            executor.execute(() -> {
                AppDatabase db = AppDatabase.getInstance(this);
                db.sessionDao().insert(newSession);

                com.sti.tanghap.data.SessionRepository repository = new com.sti.tanghap.data.SessionRepository(this);
                repository.uploadSession(newSession);

                String uid = newSession.userId != null ? newSession.userId : "";
                List<SessionEntity> allSessions = db.sessionDao().getAllForUser(uid);
                BaselineCalculator.Baseline baseline = baselineCalculator.compute(allSessions);

                runOnUiThread(() -> {
                    displaySessionDetails(newSession);
                    displayAiAssessment(newSession);
                    displayBaselineComparison(newSession, baseline);
                    generateRepresentativeWaveform(newSession);

                    findViewById(R.id.btnExport).setEnabled(true);
                    findViewById(R.id.btnExport).setAlpha(1.0f);
                });
            });
        } else {
            showEmptyState();
        }
    }

    private void loadSessionData(String id) {
        executor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            String uid = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null ?
                    com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

            SessionEntity session = db.sessionDao().getAllForUser(uid)
                    .stream().filter(s -> s.id.equals(id)).findFirst().orElse(null);

            if (session != null) {
                List<SessionEntity> allSessions = db.sessionDao().getAllForUser(uid);
                BaselineCalculator.Baseline baseline = baselineCalculator.compute(allSessions);

                runOnUiThread(() -> {
                    displaySessionDetails(session);
                    displayAiAssessment(session);
                    displayBaselineComparison(session, baseline);
                    generateRepresentativeWaveform(session);

                    findViewById(R.id.btnExport).setEnabled(true);
                    findViewById(R.id.btnExport).setAlpha(1.0f);
                });
            } else {
                runOnUiThread(this::showEmptyState);
            }
        });
    }

    private void displaySessionDetails(SessionEntity session) {
        tvDate.setText(sdf.format(new Date(session.startTimeMillis)));
        tvPeak.setText(String.format(Locale.getDefault(), "%.1f kPa", session.peakPressureKpa));
        tvInhaleDur.setText(String.format(Locale.getDefault(), "%.2f s", session.inhaleDurationMillis / 1000f));
        tvCough.setText(String.valueOf(session.coughCount));

        // Dynamic UI render for Actuations Count
        tvAct.setText(String.valueOf(session.actuationCount));
        tvAvgBpm.setText(session.averageBpm > 0 ? session.averageBpm + " BPM" : "-- BPM");
    }

    private void displayAiAssessment(SessionEntity session) {
        tvAiFindings.setText(session.aiPreliminaryInsight != null ? session.aiPreliminaryInsight : "Pattern within expected baseline.");
        tvAiObservations.setText(session.aiObservations != null ? session.aiObservations : "Respiratory rhythm analyzed.");

        if (session.otcRecommendation != null) {
            layoutOtc.setVisibility(View.VISIBLE);
            tvOtcRecommendation.setText(session.otcRecommendation);
        } else {
            layoutOtc.setVisibility(View.GONE);
        }

        if (session.riskLevelHigh) {
            btnNearbyHospitals.setVisibility(View.VISIBLE);
            tvAiFindings.setTextColor(ContextCompat.getColor(this, R.color.error));
        } else {
            btnNearbyHospitals.setVisibility(View.GONE);
            tvAiFindings.setTextColor(ContextCompat.getColor(this, R.color.text_high_emphasis));
        }
        EmergencyContactButton.bind(this, btnCallEmergency, session.riskLevelHigh);
    }

    private void displayBaselineComparison(SessionEntity session, BaselineCalculator.Baseline baseline) {
        if (baseline.avgPeakPressure == 0) {
            tvBaselineComparison.setText("First session saved. Establishing baseline...");
            return;
        }

        float diff = ((session.peakPressureKpa - baseline.avgPeakPressure) / baseline.avgPeakPressure) * 100;
        String direction = diff >= 0 ? "stronger" : "weaker";
        tvBaselineComparison.setText(String.format(Locale.getDefault(),
                "Inhalation effort is %.1f%% %s than your 7-day average baseline.", Math.abs(diff), direction));
    }

    private void findNearbyHospitals() {
        Uri gmmIntentUri = Uri.parse("geo:0,0?q=hospitals+near+me");
        Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
        mapIntent.setPackage("com.google.android.apps.maps");
        if (mapIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(mapIntent);
        } else {
            Toast.makeText(this, "Google Maps is not installed.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showEmptyState() {
        tvDate.setText("No Session Data Available");
        tvAvgBpm.setText("-- BPM");
        tvPeak.setText("-- kPa");
        tvInhaleDur.setText("-- s");
        tvCough.setText("0");
        tvAct.setText("0");
        tvAiFindings.setText("Processing Error");
        tvAiObservations.setText("Data could not be retrieved.");
        findViewById(R.id.btnExport).setEnabled(false);
        findViewById(R.id.btnExport).setAlpha(0.5f);
    }

    private void generateRepresentativeWaveform(SessionEntity session) {
        List<Entry> entries = new ArrayList<>();
        String pattern = session.detectedPattern != null ? session.detectedPattern : "";

        float frequency = 0.5f;
        float amplitude = session.peakPressureKpa > 0 ? session.peakPressureKpa : 8.0f;
        if (pattern.contains("Tachypnea")) frequency = 1.2f;
        else if (pattern.contains("Bradypnea")) frequency = 0.2f;

        for (int i = 0; i < 100; i++) {
            float val = (float) (amplitude * Math.abs(Math.sin(i * frequency * 0.2)));
            entries.add(new Entry(i, val));
        }

        LineDataSet set = new LineDataSet(entries, "Pattern");
        set.setColor(ContextCompat.getColor(this, R.color.primary_light));
        set.setLineWidth(2.5f);
        set.setDrawCircles(false);
        set.setDrawValues(false);
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        set.setDrawFilled(true);
        set.setFillColor(ContextCompat.getColor(this, R.color.primary_light));
        set.setFillAlpha(40);

        chartResultPattern.setData(new LineData(set));
        chartResultPattern.invalidate();
        chartResultPattern.animateX(800);
    }
}