package com.sti.tanghap.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.sti.tanghap.R;
import com.sti.tanghap.ble.ble.BleManager;
import com.sti.tanghap.monitor.MonitoringService;
import com.sti.tanghap.monitor.MonitoringSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Live monitoring screen. The recording itself runs in {@link MonitoringService}, so it keeps
 * going when this screen is closed or the phone is locked; this screen only displays the
 * running {@link MonitoringSession} and reattaches to it whenever it is opened again.
 */
public class LiveSessionActivity extends AppCompatActivity implements MonitoringSession.Listener {

    /** app_prefs key for the last inhaler this phone connected to (device setting, not user data). */
    public static final String PREF_LAST_DEVICE = MonitoringSession.PREF_LAST_DEVICE;

    /** How long to wait for a just-started service to create its session. */
    private static final long ATTACH_TIMEOUT_MS = 5_000;
    private static final long ATTACH_RETRY_MS = 100;
    private static final int LIVE_COUGH_BARS = 30;

    /** Set on notifications posted during a session: once the session has ended, show Alerts instead. */
    private static final String EXTRA_FROM_NOTIFICATION = "fromNotification";

    private static boolean visible = false;

    /** Intent for notifications about the running session. */
    public static Intent openFromNotification(Context ctx) {
        return new Intent(ctx, LiveSessionActivity.class).putExtra(EXTRA_FROM_NOTIFICATION, true);
    }

    /** True while this screen is on screen (used to decide whether to post a "session saved" notification). */
    public static boolean isVisible() {
        return visible;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MonitoringSession session;
    private long attachDeadline = 0;
    private boolean leaving = false;

    private BarChart chartCough;
    private TextView tvBreathingPattern, tvBreathingHint, tvLiveActuations;
    private TextView tvAiFindings, tvPossibleCondition, tvAiObservations, tvOtcRecommendation;
    private View layoutOtc;
    private MaterialButton btnNearbyHospitals;
    private MaterialButton btnCallEmergency;

    private BarDataSet coughDataSet;
    private final List<BarEntry> coughEntries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // A notification about a session that has already ended and been saved: its findings are in Alerts
        if (MonitoringService.activeSession() == null && getIntent().getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)) {
            startActivity(new Intent(this, AlertsActivity.class));
            finish();
            return;
        }
        if (MonitoringService.activeSession() == null && !startMonitoring()) {
            finish();
            return;
        }

        setContentView(R.layout.activity_live_session);
        // Keep the screen on while it is open; monitoring itself also runs with the screen off
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationIconTint(ContextCompat.getColor(this, R.color.text_high_emphasis));
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        // Leaving asks whether to end, discard, or keep monitoring in the background
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmLeave();
            }
        });

        tvAiFindings = findViewById(R.id.tvAiFindings);
        tvPossibleCondition = findViewById(R.id.tvPossibleCondition);
        tvAiObservations = findViewById(R.id.tvAiObservations);
        tvOtcRecommendation = findViewById(R.id.tvOtcRecommendation);
        layoutOtc = findViewById(R.id.layoutOtc);
        btnNearbyHospitals = findViewById(R.id.btnNearbyHospitals);
        btnCallEmergency = findViewById(R.id.btnCallEmergency);

        chartCough = findViewById(R.id.chartCough);
        tvBreathingPattern = findViewById(R.id.tvBreathingPattern);
        tvBreathingHint = findViewById(R.id.tvBreathingHint);
        tvLiveActuations = findViewById(R.id.tvLiveActuations);

        tvAiFindings.setText("Connecting to your TANGHAP device...");
        tvPossibleCondition.setVisibility(View.GONE);
        tvAiObservations.setText("Waiting for respiratory and acoustic data...");
        tvLiveActuations.setText("0 Actuations");
        layoutOtc.setVisibility(View.GONE);
        btnNearbyHospitals.setVisibility(View.GONE);

        setupCharts();

        btnNearbyHospitals.setOnClickListener(v -> NearbyHospitals.open(this));
        findViewById(R.id.btnEndSession).setOnClickListener(v -> endAndSave());
    }

    /** Starts a new monitoring session in the background service. Returns false if it cannot. */
    private boolean startMonitoring() {
        String deviceAddress = getIntent().getStringExtra("deviceAddress");
        if (deviceAddress == null || deviceAddress.isEmpty()) {
            deviceAddress = getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getString(PREF_LAST_DEVICE, null);
        }
        if (deviceAddress == null || deviceAddress.isEmpty()) {
            deviceAddress = BleManager.TANGHAP_DEVICE_ADDRESS;
        }
        if (deviceAddress == null || deviceAddress.isEmpty()) {
            Toast.makeText(this, "Scan and select your TANGHAP inhaler first.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, DeviceScanActivity.class));
            return false;
        }
        // Background monitoring of a Bluetooth device needs the Bluetooth permission (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Allow Bluetooth access to start monitoring.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, DeviceScanActivity.class));
            return false;
        }
        try {
            MonitoringService.start(this, deviceAddress);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Could not start monitoring: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        attachDeadline = System.currentTimeMillis() + ATTACH_TIMEOUT_MS;
        attach();
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        mainHandler.removeCallbacksAndMessages(null);
        // Monitoring continues in the service; this screen just stops listening
        if (session != null) session.setListener(null);
        session = null;
    }

    /** Connects this screen to the running session (the service may need a moment to create it). */
    private void attach() {
        MonitoringSession s = MonitoringService.activeSession();
        if (s == null) {
            if (System.currentTimeMillis() < attachDeadline) {
                mainHandler.postDelayed(this::attach, ATTACH_RETRY_MS);
            } else if (!leaving) {
                Toast.makeText(this, "Monitoring is not running. Start a new session from the dashboard.",
                        Toast.LENGTH_LONG).show();
                finish();
            }
            return;
        }
        session = s;
        restoreCoughChart(s.recentSoundLevels());
        s.setListener(this);
    }

    private void confirmLeave() {
        if (session == null || session.isEnding()) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Leave live monitoring?")
                .setMessage("Monitoring can keep running in the background (you will see a TANGHAP "
                        + "notification), or you can end it now.")
                .setPositiveButton("End & Save", (d, w) -> endAndSave())
                .setNeutralButton("Keep in Background", (d, w) -> {
                    leaving = true;
                    Toast.makeText(this, "Monitoring continues in the background. Tap the TANGHAP "
                            + "notification to return.", Toast.LENGTH_LONG).show();
                    finish();
                })
                .setNegativeButton("Discard", (d, w) -> confirmDiscard())
                .show();
    }

    private void confirmDiscard() {
        new AlertDialog.Builder(this)
                .setTitle("Discard this session?")
                .setMessage("All readings from this session will be deleted and not saved.")
                .setPositiveButton("Discard", (d, w) -> {
                    leaving = true;
                    if (session != null) session.discard();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void endAndSave() {
        if (session == null) return;
        leaving = true;
        tvAiFindings.setText("Saving session...");
        session.endAndSave();
    }

    // =========================================================
    // SESSION UPDATES (main thread)
    // =========================================================

    @Override
    public void onUpdate(MonitoringSession.Snapshot s) {
        // Breathing pattern from the breathing sounds; its rate and the inhalation effort are not shown
        tvBreathingPattern.setText(s.pattern);
        tvBreathingHint.setText(s.breathingInsight);
        tvLiveActuations.setText(s.actuations + (s.actuations == 1 ? " Actuation" : " Actuations"));
        if (!leaving) tvAiFindings.setText(s.aiLabel);

        if (!s.hasPrediction) {
            tvAiObservations.setText("Processing acoustic spectral data and respiratory patterns...");
            tvPossibleCondition.setVisibility(View.GONE);
            layoutOtc.setVisibility(View.GONE);
        } else {
            tvAiObservations.setText(s.observations);
            tvPossibleCondition.setVisibility(View.VISIBLE);
            tvPossibleCondition.setText(s.possibleCondition);
            layoutOtc.setVisibility(View.VISIBLE);
            tvOtcRecommendation.setText(s.medicationGuidance);
        }

        btnNearbyHospitals.setVisibility(s.worsening ? View.VISIBLE : View.GONE);
        EmergencyContactButton.bind(this, btnCallEmergency, s.worsening);

        if (getSupportActionBar() != null) {
            String status = s.connected ? "Connected" : "Not connected";
            if (s.batteryPercent != null && s.batteryPercent <= com.sti.tanghap.domain.BatteryAlert.LOW_PERCENT) {
                status += " • Battery low";
            }
            getSupportActionBar().setSubtitle(status);
        }
    }

    @Override
    public void onSoundLevel(int levelPercent) {
        addCoughBar(levelPercent);
        refreshCoughChart();
    }

    @Override
    public void onMessage(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onFinished(String sessionId, String message) {
        session = null;
        if (sessionId != null) {
            Intent intent = new Intent(this, SessionResultActivity.class);
            intent.putExtra("sessionId", sessionId);
            startActivity(intent);
        } else if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
        finish();
    }

    // =========================================================
    // CHARTS
    // =========================================================

    private void setupCharts() {
        coughDataSet = new BarDataSet(new ArrayList<>(), "Cough");
        coughDataSet.setColor(ContextCompat.getColor(this, R.color.error));
        coughDataSet.setDrawValues(false);
        chartCough.setData(new BarData(coughDataSet));
        styleChart(chartCough);
        // Fixed 0-100 % scale: an auto-scaled axis makes quiet background look as tall as a cough
        chartCough.getAxisLeft().setAxisMinimum(0f);
        chartCough.getAxisLeft().setAxisMaximum(100f);
    }

    /** Redraws the sound chart from the session's recent history (e.g. after returning to this screen). */
    private void restoreCoughChart(int[] coughBars) {
        coughEntries.clear();
        for (int v : coughBars) addCoughBar(v);
        refreshCoughChart();
    }

    private void addCoughBar(int value) {
        coughEntries.add(new BarEntry(coughEntries.size(), value));
        if (coughEntries.size() > LIVE_COUGH_BARS) {
            coughEntries.remove(0);
            for (int i = 0; i < coughEntries.size(); i++) coughEntries.get(i).setX(i);
        }
    }

    private void refreshCoughChart() {
        coughDataSet.setValues(coughEntries);
        chartCough.getData().notifyDataChanged();
        chartCough.notifyDataSetChanged();
        chartCough.invalidate();
    }

    private void styleChart(com.github.mikephil.charting.charts.BarLineChartBase<?> chart) {
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.getAxisRight().setEnabled(false);
        chart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        chart.getXAxis().setTextColor(ContextCompat.getColor(this, R.color.text_low_emphasis));
        chart.getAxisLeft().setTextColor(ContextCompat.getColor(this, R.color.text_low_emphasis));
        chart.invalidate();
    }
}
