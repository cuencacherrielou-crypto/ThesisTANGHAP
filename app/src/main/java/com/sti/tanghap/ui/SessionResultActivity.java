package com.sti.tanghap.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.sti.tanghap.R;
import com.sti.tanghap.ai.CoughReview;
import com.sti.tanghap.ai.SavedAssessment;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.HealthPrefs;
import com.sti.tanghap.data.SessionEvent;
import com.sti.tanghap.domain.SessionTimeline;
import com.sti.tanghap.data.SessionDao;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.data.SessionRepository;
import com.sti.tanghap.data.SessionStore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SessionResultActivity extends AppCompatActivity {
    // Opened with "sessionId": the session was already saved by MonitoringService (or opened from History)


    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // STANDARD 12-HOUR TIME FORMAT WITH AM/PM DISPLAY
    private final SimpleDateFormat sdf = new SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm a", Locale.getDefault());
    private final SimpleDateFormat timeOnly = new SimpleDateFormat("hh:mm a", Locale.getDefault());

    private TextView tvAvgBpm, tvPeak, tvInhaleDur, tvCough, tvAct, tvDate;
    private TextView tvAiFindings, tvPossibleCondition, tvAiObservations, tvOtcRecommendation, tvBaselineComparison;
    private TextView tvTimelineSummary, tvCoughReviewHint, tvBpmTimelineLabel;
    private LinearLayout timelineContainer;
    /** The session on screen; cough reviews change and save it. */
    private SessionEntity shownSession;
    private LineChart chartBpmTimeline;
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
        tvPossibleCondition = findViewById(R.id.tvPossibleCondition);
        tvAiObservations = findViewById(R.id.tvAiObservations);
        tvOtcRecommendation = findViewById(R.id.tvOtcRecommendation);
        tvBaselineComparison = findViewById(R.id.tvBaselineComparison);
        layoutOtc = findViewById(R.id.layoutOtc);
        btnNearbyHospitals = findViewById(R.id.btnNearbyHospitals);
        btnCallEmergency = findViewById(R.id.btnCallEmergency);

        chartResultPattern = findViewById(R.id.chartResultPattern);
        chartBpmTimeline = findViewById(R.id.chartBpmTimeline);
        tvTimelineSummary = findViewById(R.id.tvTimelineSummary);
        tvCoughReviewHint = findViewById(R.id.tvCoughReviewHint);
        timelineContainer = findViewById(R.id.timelineContainer);
        tvBpmTimelineLabel = findViewById(R.id.tvBpmTimelineLabel);

        setupChart();
        setupBpmTimelineChart();

        currentSessionId = getIntent().getStringExtra("sessionId");

        // Opened from a notification this screen may be the only one, so go to the dashboard explicitly
        findViewById(R.id.btnGoDashboard).setOnClickListener(v -> {
            startActivity(new Intent(this, DashboardActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
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
        XAxis x = chartResultPattern.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setTextColor(ContextCompat.getColor(this, R.color.text_low_emphasis));
        x.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.getDefault(), "%.0fs", value);
            }
        });
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
                    session.baselineComparison = SessionStore.baselineText(session, SessionStore.baselineFor(dao, session));
                }
                runOnUiThread(() -> showSession(session));
            } else {
                runOnUiThread(this::showEmptyState);
            }
        });
    }

    private void showSession(SessionEntity session) {
        shownSession = session;
        displaySessionDetails(session);
        displayAiAssessment(session);
        tvBaselineComparison.setText(session.baselineComparison);
        displayPressureTrace(session);
        displayTimeline(session);

        boolean canExport = currentSessionId != null;
        findViewById(R.id.btnExport).setEnabled(canExport);
        findViewById(R.id.btnExport).setAlpha(canExport ? 1.0f : 0.5f);
    }

    private void displaySessionDetails(SessionEntity session) {
        long durationSec = Math.max(0, (session.endTimeMillis - session.startTimeMillis) / 1000);
        tvDate.setText(sdf.format(new Date(session.startTimeMillis))
                + " to " + timeOnly.format(new Date(session.endTimeMillis))
                + String.format(Locale.getDefault(), " (%d min %02d s)", durationSec / 60, durationSec % 60));
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

        SavedAssessment saved = SavedAssessment.of(session, HealthPrefs.loadProfile(this));
        if (saved.possibleCondition != null) {
            tvPossibleCondition.setVisibility(View.VISIBLE);
            tvPossibleCondition.setText(saved.possibleCondition);
        } else {
            tvPossibleCondition.setVisibility(View.GONE);
        }

        if (saved.medicationGuidance != null) {
            layoutOtc.setVisibility(View.VISIBLE);
            tvOtcRecommendation.setText(saved.medicationGuidance);
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
        tvAiFindings.setText("Session not found");
        tvAiObservations.setText("This session is not stored on this phone for the signed-in account.");
        tvBaselineComparison.setText("--");
        tvPossibleCondition.setVisibility(View.GONE);
        tvTimelineSummary.setText("No time-stamped readings.");
        timelineContainer.removeAllViews();
        tvCoughReviewHint.setVisibility(View.GONE);
        findViewById(R.id.btnExport).setEnabled(false);
        findViewById(R.id.btnExport).setAlpha(0.5f);
    }

    /**
     * Plots the pressure actually recorded during the session (no synthetic waveform),
     * placed at the time each point was recorded (seconds from the session start).
     */
    private void displayPressureTrace(SessionEntity session) {
        List<Entry> entries = new ArrayList<>();
        if (session.pressureTrace != null && !session.pressureTrace.isEmpty()) {
            String[] parts = session.pressureTrace.split(",");
            String[] times = session.pressureTraceTimes != null ? session.pressureTraceTimes.split(",") : null;
            // Older sessions have no time stamps: spread the points evenly over the session
            boolean timed = times != null && times.length == parts.length;
            float sessionSec = Math.max(1f, (session.endTimeMillis - session.startTimeMillis) / 1000f);
            float lastX = -1f;
            for (int i = 0; i < parts.length; i++) {
                try {
                    float x = timed ? Long.parseLong(times[i]) / 1000f : sessionSec * i / parts.length;
                    if (x < lastX) continue; // the chart needs ascending x values
                    entries.add(new Entry(x, Float.parseFloat(parts[i])));
                    lastX = x;
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

    // =========================================================
    // TIME-STAMPED TIMELINE
    // =========================================================

    private void setupBpmTimelineChart() {
        chartBpmTimeline.getDescription().setEnabled(false);
        chartBpmTimeline.getLegend().setEnabled(false);
        chartBpmTimeline.getAxisRight().setEnabled(false);
        chartBpmTimeline.getAxisLeft().setTextColor(ContextCompat.getColor(this, R.color.text_low_emphasis));
        chartBpmTimeline.setTouchEnabled(false);
        chartBpmTimeline.setNoDataText("No breathing-rate readings in this session.");
        chartBpmTimeline.setNoDataTextColor(ContextCompat.getColor(this, R.color.text_low_emphasis));
        XAxis x = chartBpmTimeline.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setLabelCount(4);
        x.setTextColor(ContextCompat.getColor(this, R.color.text_low_emphasis));
    }

    private void displayTimeline(SessionEntity session) {
        List<SessionEvent> events = SessionEvent.decode(session.eventLog);

        // Summary: breathing-rate readings, inhalations and data quality
        StringBuilder summary = new StringBuilder();
        String rates = SessionTimeline.breathingRateSummary(events);
        String inhalations = SessionTimeline.inhalationSummary(events);
        if (rates != null) summary.append("Breathing rate: ").append(rates);
        if (inhalations != null) {
            if (summary.length() > 0) summary.append('\n');
            summary.append("Inhalations: ").append(inhalations);
        }
        if (session.droppedPackets > 0) {
            if (summary.length() > 0) summary.append('\n');
            summary.append("Data quality: ").append(session.droppedPackets)
                    .append(session.droppedPackets == 1 ? " sensor packet was" : " sensor packets were")
                    .append(" lost over Bluetooth; readings may be slightly incomplete.");
        }
        if (summary.length() == 0) {
            summary.append(events.isEmpty()
                    ? "This session was recorded before time-stamped readings were added."
                    : "No breathing-rate readings or inhalations were recorded.");
        }
        tvTimelineSummary.setText(summary);

        // Breathing rate over time, labelled with the clock time
        List<SessionEvent> bpm = SessionTimeline.ofType(events, SessionEvent.Type.BREATHING_RATE);
        if (bpm.isEmpty()) {
            tvBpmTimelineLabel.setVisibility(View.GONE);
            chartBpmTimeline.setVisibility(View.GONE);
        } else {
            tvBpmTimelineLabel.setVisibility(View.VISIBLE);
            chartBpmTimeline.setVisibility(View.VISIBLE);
            List<Entry> entries = new ArrayList<>();
            for (SessionEvent e : bpm) {
                entries.add(new Entry((e.timeMillis - session.startTimeMillis) / 1000f, e.value));
            }
            final long start = session.startTimeMillis;
            final SimpleDateFormat clock = new SimpleDateFormat("hh:mm a", Locale.getDefault());
            chartBpmTimeline.getXAxis().setValueFormatter(new ValueFormatter() {
                @Override
                public String getFormattedValue(float value) {
                    return clock.format(new Date(start + Math.round(value * 1000)));
                }
            });
            LineDataSet set = new LineDataSet(entries, "BPM");
            set.setColor(ContextCompat.getColor(this, R.color.accent_teal));
            set.setCircleColor(ContextCompat.getColor(this, R.color.accent_teal));
            set.setCircleRadius(2f);
            set.setLineWidth(2f);
            set.setDrawValues(false);
            chartBpmTimeline.setData(new LineData(set));
            chartBpmTimeline.invalidate();
        }

        // Coughs, wheezes, inhaler presses and connection events, each with its time.
        // Cough rows can be reviewed: "Not mine" removes a cough, "Count it" adds one back.
        timelineContainer.removeAllViews();
        boolean canReview = currentSessionId != null && session.userId != null;
        boolean hasCoughs = false;
        SimpleDateFormat clock = new SimpleDateFormat("hh:mm:ss a", Locale.getDefault());
        for (int i = 0; i < events.size(); i++) {
            SessionEvent e = events.get(i);
            if (e.type == SessionEvent.Type.BREATHING_RATE || e.type == SessionEvent.Type.INHALATION) continue;
            hasCoughs |= e.isCoughEntry();
            timelineContainer.addView(timelineRow(clock.format(new Date(e.timeMillis)) + "  " + e.describe(),
                    canReview && e.isCoughEntry() ? i : -1, e));
        }
        tvCoughReviewHint.setVisibility(canReview && hasCoughs ? View.VISIBLE : View.GONE);
    }

    private View timelineRow(String text, int reviewIndex, SessionEvent e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        boolean counted = e.type != SessionEvent.Type.COUGH_IGNORED && e.type != SessionEvent.Type.COUGH_REMOVED;
        tv.setTextColor(ContextCompat.getColor(this, counted ? R.color.text_medium_emphasis : R.color.text_low_emphasis));
        tv.setPadding(0, 6, 0, 6);
        row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (reviewIndex >= 0) {
            boolean isCounted = e.type == SessionEvent.Type.COUGH;
            MaterialButton btn = new MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle);
            btn.setText(isCounted ? "Not mine" : "Count it");
            btn.setTextSize(11);
            btn.setTextColor(ContextCompat.getColor(this, isCounted ? R.color.error : R.color.accent_teal));
            btn.setOnClickListener(v -> reviewCough(reviewIndex,
                    isCounted ? SessionEvent.Type.COUGH_REMOVED : SessionEvent.Type.COUGH));
            row.addView(btn, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        return row;
    }

    /**
     * Removes a cough that was not the user's, or counts one back in, then recalculates the
     * cough count, possible condition and medicine guidance and saves and syncs the session.
     */
    private void reviewCough(int eventIndex, SessionEvent.Type newType) {
        SessionEntity session = shownSession;
        if (session == null) return;
        List<SessionEvent> events = SessionEvent.decode(session.eventLog);
        if (eventIndex < 0 || eventIndex >= events.size() || !events.get(eventIndex).isCoughEntry()) return;
        events.set(eventIndex, events.get(eventIndex).withType(newType));
        CoughReview.applyTo(session, events, HealthPrefs.loadProfile(this));

        executor.execute(() -> {
            AppDatabase.getInstance(this).sessionDao().insert(session);
            new SessionRepository(this).uploadSession(session);
            runOnUiThread(() -> {
                showSession(session);
                Toast.makeText(this, newType == SessionEvent.Type.COUGH
                        ? "Cough counted. Results updated." : "Cough removed. Results updated.", Toast.LENGTH_SHORT).show();
            });
        });
    }
}
