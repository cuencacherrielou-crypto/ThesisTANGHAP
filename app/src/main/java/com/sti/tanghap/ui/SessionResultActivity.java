package com.sti.tanghap.ui;

import android.content.Intent;
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
import com.google.firebase.auth.FirebaseAuth;
import com.sti.tanghap.R;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.SessionDao;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.data.SessionRepository;
import com.sti.tanghap.domain.BaselineCalculator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SessionResultActivity extends AppCompatActivity {
    // Extras sent by LiveSessionActivity when a session ends
    public static final String EXTRA_START = "sessionStart";
    public static final String EXTRA_END = "sessionEnd";
    public static final String EXTRA_MAX_EFFORT = "maxEffort";
    public static final String EXTRA_INHALE_MS = "inhaleMillis";
    public static final String EXTRA_AVG_BPM = "avgBpm";
    public static final String EXTRA_PATTERN = "breathingPattern";
    public static final String EXTRA_COUGH_COUNT = "coughCount";
    public static final String EXTRA_ACTUATIONS = "actuationCount";
    public static final String EXTRA_COUGH_FINDING = "coughFinding";
    public static final String EXTRA_INSIGHT = "aiInsight";
    public static final String EXTRA_OBSERVATIONS = "aiObservations";
    public static final String EXTRA_OTC = "otcRecommendation";
    public static final String EXTRA_RISK_HIGH = "riskLevelHigh";
    public static final String EXTRA_RECOMMENDATION = "recommendation";
    public static final String EXTRA_TRACE = "pressureTrace";

    private static final long BASELINE_WINDOW_MS = TimeUnit.DAYS.toMillis(7);

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
        btnNearbyHospitals.setOnClickListener(v -> NearbyHospitals.open(this));

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
        } else if (getIntent().hasExtra(EXTRA_START)) {
            // Processing Live Session Results passed from LiveSessionActivity
            processAndSaveLiveSession();
        } else {
            showEmptyState();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void setupChart() {
        chartResultPattern.getDescription().setEnabled(false);
        chartResultPattern.getLegend().setEnabled(false);
        chartResultPattern.getAxisRight().setEnabled(false);
        chartResultPattern.getXAxis().setEnabled(false);
        chartResultPattern.getAxisLeft().setDrawGridLines(false);
        chartResultPattern.getAxisLeft().setDrawLabels(false);
        chartResultPattern.setTouchEnabled(false);
        chartResultPattern.setNoDataText("No recorded pressure trace for this session.");
        chartResultPattern.setNoDataTextColor(ContextCompat.getColor(this, R.color.text_low_emphasis));
    }

    private static String currentUid() {
        return FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
    }

    private void processAndSaveLiveSession() {
        Intent in = getIntent();
        String uid = currentUid();

        currentSessionId = UUID.randomUUID().toString();
        SessionEntity newSession = new SessionEntity(currentSessionId);
        newSession.userId = uid;
        newSession.startTimeMillis = in.getLongExtra(EXTRA_START, System.currentTimeMillis());
        newSession.endTimeMillis = in.getLongExtra(EXTRA_END, System.currentTimeMillis());
        newSession.peakPressureKpa = in.getFloatExtra(EXTRA_MAX_EFFORT, 0f);
        newSession.inhaleDurationMillis = in.getLongExtra(EXTRA_INHALE_MS, 0L);
        newSession.averageBpm = in.getIntExtra(EXTRA_AVG_BPM, 0);
        newSession.detectedPattern = in.getStringExtra(EXTRA_PATTERN);
        newSession.coughCount = in.getIntExtra(EXTRA_COUGH_COUNT, 0);
        newSession.actuationCount = in.getIntExtra(EXTRA_ACTUATIONS, 0);
        newSession.coughFinding = in.getStringExtra(EXTRA_COUGH_FINDING);
        newSession.aiPreliminaryInsight = in.getStringExtra(EXTRA_INSIGHT);
        newSession.aiObservations = in.getStringExtra(EXTRA_OBSERVATIONS);
        newSession.otcRecommendation = in.getStringExtra(EXTRA_OTC);
        newSession.riskLevelHigh = in.getBooleanExtra(EXTRA_RISK_HIGH, false);
        newSession.recommendation = in.getStringExtra(EXTRA_RECOMMENDATION);
        newSession.pressureTrace = in.getStringExtra(EXTRA_TRACE);

        executor.execute(() -> {
            SessionDao dao = AppDatabase.getInstance(this).sessionDao();
            BaselineCalculator.Baseline baseline = computeBaseline(dao, newSession);
            newSession.baselineComparison = baselineText(newSession, baseline);

            if (uid != null) {
                dao.insert(newSession);
                new SessionRepository(this).uploadSession(newSession);
            }

            runOnUiThread(() -> {
                if (uid == null) {
                    Toast.makeText(this, "Not signed in: this session was not saved.", Toast.LENGTH_LONG).show();
                    currentSessionId = null;
                }
                showSession(newSession);
            });
        });
    }

    private void loadSessionData(String id) {
        String uid = currentUid();
        if (uid == null) {
            showEmptyState();
            return;
        }
        executor.execute(() -> {
            SessionDao dao = AppDatabase.getInstance(this).sessionDao();
            SessionEntity session = dao.getByIdForUser(id, uid);

            if (session != null) {
                if (session.baselineComparison == null) {
                    session.baselineComparison = baselineText(session, computeBaseline(dao, session));
                }
                runOnUiThread(() -> showSession(session));
            } else {
                runOnUiThread(this::showEmptyState);
            }
        });
    }

    /** Baseline = this user's sessions in the 7 days before this session, excluding the session itself. */
    private BaselineCalculator.Baseline computeBaseline(SessionDao dao, SessionEntity session) {
        List<SessionEntity> previous = new ArrayList<>();
        if (session.userId != null) {
            for (SessionEntity s : dao.getSinceForUser(session.userId, session.startTimeMillis - BASELINE_WINDOW_MS)) {
                if (!s.id.equals(session.id) && s.startTimeMillis < session.startTimeMillis) previous.add(s);
            }
        }
        return baselineCalculator.compute(previous);
    }

    private static String baselineText(SessionEntity session, BaselineCalculator.Baseline baseline) {
        if (baseline.avgPeakPressure <= 0) {
            return "No sessions in the previous 7 days yet. Establishing baseline...";
        }
        float diff = ((session.peakPressureKpa - baseline.avgPeakPressure) / baseline.avgPeakPressure) * 100;
        String direction = diff >= 0 ? "stronger" : "weaker";
        return String.format(Locale.getDefault(),
                "Inhalation effort is %.1f%% %s than your 7-day average baseline.", Math.abs(diff), direction);
    }

    private void showSession(SessionEntity session) {
        displaySessionDetails(session);
        displayAiAssessment(session);
        tvBaselineComparison.setText(session.baselineComparison);
        displayPressureTrace(session);

        boolean canExport = currentSessionId != null;
        findViewById(R.id.btnExport).setEnabled(canExport);
        findViewById(R.id.btnExport).setAlpha(canExport ? 1.0f : 0.5f);
    }

    private void displaySessionDetails(SessionEntity session) {
        tvDate.setText(sdf.format(new Date(session.startTimeMillis)));
        tvPeak.setText(String.format(Locale.getDefault(), "%.1f kPa", session.peakPressureKpa));
        tvInhaleDur.setText(session.inhaleDurationMillis > 0
                ? String.format(Locale.getDefault(), "%.2f s", session.inhaleDurationMillis / 1000f)
                : "-- s");
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

    private void showEmptyState() {
        tvDate.setText("No Session Data Available");
        tvAvgBpm.setText("-- BPM");
        tvPeak.setText("-- kPa");
        tvInhaleDur.setText("-- s");
        tvCough.setText("0");
        tvAct.setText("0");
        tvAiFindings.setText("Processing Error");
        tvAiObservations.setText("Data could not be retrieved.");
        tvBaselineComparison.setText("--");
        findViewById(R.id.btnExport).setEnabled(false);
        findViewById(R.id.btnExport).setAlpha(0.5f);
    }

    /** Plots the pressure actually recorded during the session (no synthetic waveform). */
    private void displayPressureTrace(SessionEntity session) {
        List<Entry> entries = new ArrayList<>();
        if (session.pressureTrace != null && !session.pressureTrace.isEmpty()) {
            String[] parts = session.pressureTrace.split(",");
            for (int i = 0; i < parts.length; i++) {
                try {
                    entries.add(new Entry(i, Float.parseFloat(parts[i])));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (entries.isEmpty()) {
            chartResultPattern.clear();
            return;
        }

        LineDataSet set = new LineDataSet(entries, "Pressure");
        set.setColor(ContextCompat.getColor(this, R.color.primary_light));
        set.setLineWidth(2f);
        set.setDrawCircles(false);
        set.setDrawValues(false);
        set.setDrawFilled(true);
        set.setFillColor(ContextCompat.getColor(this, R.color.primary_light));
        set.setFillAlpha(40);

        chartResultPattern.setData(new LineData(set));
        chartResultPattern.invalidate();
        chartResultPattern.animateX(800);
    }
}
