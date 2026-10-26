package com.sti.tanghap.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.sti.tanghap.R;
import com.sti.tanghap.ai.BreathingAnalyzer;
import com.sti.tanghap.ai.CareAction;
import com.sti.tanghap.ai.CoughReview;
import com.sti.tanghap.ai.PatientProfile;
import com.sti.tanghap.ai.SavedAssessment;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.HealthPrefs;
import com.sti.tanghap.data.SessionEvent;
import com.sti.tanghap.domain.CoughHistory;
import com.sti.tanghap.domain.SessionTimeline;
import com.sti.tanghap.data.SessionDao;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.data.SessionRepository;
import com.sti.tanghap.data.SessionStore;
import java.text.SimpleDateFormat;
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

    private TextView tvBreathingPattern, tvCough, tvAct, tvDate;
    private TextView tvAiFindings, tvPossibleCondition, tvAiObservations, tvOtcRecommendation, tvBaselineComparison;
    private TextView tvTimelineSummary, tvCoughReviewHint;
    private LinearLayout timelineContainer;
    /** The session on screen; cough reviews change and save it. */
    private SessionEntity shownSession;
    private View layoutOtc;
    private View layoutAiHeadline, layoutAiDetails, layoutAiKeyFacts;
    private TextView tvAiHeadline, tvAiAdvice, tvKeyCoughs, tvKeyWheezes, tvKeyBreathing, btnAiDetails;
    private MaterialButton btnNearbyHospitals;
    private MaterialButton btnCallEmergency;
    private String currentSessionId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_result);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        // Standard Metrics
        tvBreathingPattern = findViewById(R.id.tvBreathingPattern);
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
        layoutAiHeadline = findViewById(R.id.layoutAiHeadline);
        layoutAiDetails = findViewById(R.id.layoutAiDetails);
        layoutAiKeyFacts = findViewById(R.id.layoutAiKeyFacts);
        tvAiHeadline = findViewById(R.id.tvAiHeadline);
        tvAiAdvice = findViewById(R.id.tvAiAdvice);
        tvKeyCoughs = findViewById(R.id.tvKeyCoughs);
        tvKeyWheezes = findViewById(R.id.tvKeyWheezes);
        tvKeyBreathing = findViewById(R.id.tvKeyBreathing);
        btnAiDetails = findViewById(R.id.btnAiDetails);

        // The headline and key numbers carry the result; the full text is one tap away
        btnAiDetails.setVisibility(View.VISIBLE);
        setDetailsShown(false);
        btnAiDetails.setOnClickListener(v -> setDetailsShown(layoutAiDetails.getVisibility() != View.VISIBLE));
        btnNearbyHospitals = findViewById(R.id.btnNearbyHospitals);
        btnCallEmergency = findViewById(R.id.btnCallEmergency);

        tvTimelineSummary = findViewById(R.id.tvTimelineSummary);
        tvCoughReviewHint = findViewById(R.id.tvCoughReviewHint);
        timelineContainer = findViewById(R.id.timelineContainer);

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
                // Always rebuilt, so older sessions' saved inhalation-effort comparison is not shown
                session.baselineComparison = SessionStore.baselineText(session, SessionStore.baselineFor(dao, session));
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
        tvCough.setText(String.valueOf(session.coughCount));

        // Dynamic UI render for Actuations Count
        tvAct.setText(String.valueOf(session.actuationCount));
        // Breathing pattern from the breathing sounds; the rate and inhalation effort are not shown
        tvBreathingPattern.setText(session.detectedPattern != null ? session.detectedPattern : "--");
    }

    private void setDetailsShown(boolean shown) {
        layoutAiDetails.setVisibility(shown ? View.VISIBLE : View.GONE);
        btnAiDetails.setText(shown ? "Hide full details ▴" : "Show full details ▾");
    }

    /** Large "what to do" headline, colored by urgency, and the session's key facts. */
    private void displayHeadline(SessionEntity session, String possibleCondition) {
        CareAction action = CareAction.of(session.riskLevelHigh, session.recommendation, possibleCondition);
        int color = action == CareAction.HOSPITAL ? R.color.error
                : action == CareAction.CLINIC ? R.color.warning : R.color.success;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(ContextCompat.getColor(this, color));
        bg.setCornerRadius(16 * getResources().getDisplayMetrics().density);
        layoutAiHeadline.setBackground(bg);
        layoutAiHeadline.setVisibility(View.VISIBLE);
        tvAiHeadline.setText(action.headline);
        tvAiAdvice.setText(action.advice);

        int wheezes = SessionTimeline.ofType(SessionEvent.decode(session.eventLog), SessionEvent.Type.WHEEZE).size();
        tvKeyCoughs.setText(String.valueOf(session.coughCount));
        tvKeyWheezes.setText(String.valueOf(wheezes));
        tvKeyBreathing.setText(BreathingAnalyzer.shortLabel(session.detectedPattern));
        layoutAiKeyFacts.setVisibility(View.VISIBLE);
    }

    private void displayAiAssessment(SessionEntity session) {
        tvAiFindings.setText(session.aiPreliminaryInsight != null ? session.aiPreliminaryInsight : "Pattern within expected baseline.");
        tvAiObservations.setText(session.aiObservations != null ? session.aiObservations : "Respiratory rhythm analyzed.");

        SavedAssessment saved = SavedAssessment.of(session, HealthPrefs.loadProfile(this));
        displayHeadline(session, saved.possibleCondition);
        if (saved.possibleCondition != null) {
            tvPossibleCondition.setVisibility(View.VISIBLE);
            tvPossibleCondition.setText(saved.possibleCondition);
            tvPossibleCondition.setTextColor(ContextCompat.getColor(this,
                    com.sti.tanghap.ai.AssessmentEngine.CATEGORY_STANDARD.equals(saved.possibleCondition)
                            ? R.color.text_medium_emphasis : R.color.warning));
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
        tvBreathingPattern.setText("--");
        tvCough.setText("0");
        tvAct.setText("0");
        layoutAiHeadline.setVisibility(View.GONE);
        layoutAiKeyFacts.setVisibility(View.GONE);
        setDetailsShown(true);
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

    // =========================================================
    // TIME-STAMPED TIMELINE
    // =========================================================

    private void displayTimeline(SessionEntity session) {
        List<SessionEvent> events = SessionEvent.decode(session.eventLog);

        // Summary: data quality (breathing-rate and inhalation values are not shown for now)
        StringBuilder summary = new StringBuilder();
        if (session.droppedPackets > 0) {
            summary.append("Data quality: ").append(session.droppedPackets)
                    .append(session.droppedPackets == 1 ? " sensor packet was" : " sensor packets were")
                    .append(" lost over Bluetooth; readings may be slightly incomplete.");
        }
        if (summary.length() == 0) {
            summary.append(events.isEmpty()
                    ? "This session was recorded before time-stamped readings were added."
                    : "Coughs, wheezes and inhaler presses are listed below with their times.");
        }
        tvTimelineSummary.setText(summary);

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
        tv.setTextSize(14);
        boolean counted = e.type != SessionEvent.Type.COUGH_IGNORED && e.type != SessionEvent.Type.COUGH_REMOVED;
        tv.setTextColor(ContextCompat.getColor(this, counted ? R.color.text_medium_emphasis : R.color.text_low_emphasis));
        tv.setPadding(0, 6, 0, 6);
        row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (reviewIndex >= 0) {
            boolean isCounted = e.type == SessionEvent.Type.COUGH;
            MaterialButton btn = new MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle);
            btn.setText(isCounted ? "Not mine" : "Count it");
            btn.setTextSize(13);
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
        PatientProfile profile = HealthPrefs.loadProfile(this);

        executor.execute(() -> {
            // Earlier sessions' coughs and wheezes decide whether the cough lasts and the wheezing recurs
            SessionDao dao = AppDatabase.getInstance(this).sessionDao();
            List<SessionEntity> earlier = dao.getSinceForUser(session.userId,
                    session.startTimeMillis - CoughHistory.LOOKBACK_MS);
            CoughReview.applyTo(session, events, profile,
                    CoughHistory.coughTimesBefore(earlier, session.startTimeMillis),
                    CoughHistory.wheezeTimesBefore(earlier, session.startTimeMillis));
            dao.insert(session);
            new SessionRepository(this).uploadSession(session);
            runOnUiThread(() -> {
                showSession(session);
                Toast.makeText(this, newType == SessionEvent.Type.COUGH
                        ? "Cough counted. Results updated." : "Cough removed. Results updated.", Toast.LENGTH_SHORT).show();
            });
        });
    }
}
