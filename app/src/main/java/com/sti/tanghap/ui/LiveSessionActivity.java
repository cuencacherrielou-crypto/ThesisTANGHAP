package com.sti.tanghap.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.sti.tanghap.R;
import com.sti.tanghap.ai.AiCoughClassifier;
import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.ai.BreathingAnalyzer;
import com.sti.tanghap.ai.CoughClass;
import com.sti.tanghap.ai.processor.MfccExtractor;
import com.sti.tanghap.ble.ble.BleDeviceItem;
import com.sti.tanghap.ble.ble.BleListener;
import com.sti.tanghap.ble.ble.BleManager;
import com.sti.tanghap.data.ConnectionLog;
import com.sti.tanghap.data.HealthPrefs;
import com.sti.tanghap.domain.InhalationTracker;
import com.sti.tanghap.notify.NotificationHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class LiveSessionActivity extends AppCompatActivity implements BleListener {
    private static final String TAG = "LiveSessionActivity";

    /** app_prefs key for the last inhaler this phone connected to (device setting, not user data). */
    public static final String PREF_LAST_DEVICE = "last_device_address";

    // Pressure analysis
    private static final long BREATH_WINDOW_MS = 30_000;      // BPM is measured over the last 30 s
    private static final long BPM_SAMPLE_INTERVAL_MS = 5_000; // how often a BPM reading enters the session average
    private static final int MAX_RECORDED_SAMPLES = 200_000;  // memory cap for the stored session trace
    private static final int TRACE_POINTS = 200;              // points saved for the result chart

    // Cough model audio windows (16 kHz 16-bit mono)
    private static final int CLIP_BYTES = MfccExtractor.CLIP_SAMPLES * 2; // 2 s, the clip length the model was trained on
    private static final int CLIP_HOP_BYTES = CLIP_BYTES / 2;             // 1 s step, so a cough on a window edge is still heard whole

    private BleManager ble;
    private String deviceAddress;
    private LineChart chartBreathing;
    private BarChart chartCough;
    private TextView tvBreathingPattern, tvBreathingRate, tvLiveEffort, tvLiveActuations;

    private TextView tvAiFindings, tvAiObservations, tvOtcRecommendation;
    private View layoutOtc;
    private MaterialButton btnNearbyHospitals;
    private MaterialButton btnCallEmergency;
    private final BreathingAnalyzer breathingAnalyzer = new BreathingAnalyzer();
    private final AssessmentEngine assessmentEngine = new AssessmentEngine();
    private final InhalationTracker inhalationTracker = new InhalationTracker(BreathingAnalyzer.INHALATION_THRESHOLD_KPA);
    private boolean worseningNotified = false;

    // Auto-reconnect after an unexpected Bluetooth drop (UI thread)
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 3000;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private LineDataSet breathingDataSet;
    private BarDataSet coughDataSet;
    private int breathingIndex = 0;
    private final List<BarEntry> coughEntries = new ArrayList<>();

    // ---- UI-thread state ----
    private final long sessionStartMillis = System.currentTimeMillis();
    private final ArrayDeque<PressurePacket> pressureWindow = new ArrayDeque<>();
    private float[] recordedSamples = new float[4096];
    private int recordedCount = 0;
    private float maxInhaleEffort = 0.0f;
    private int bpmSum = 0;
    private int bpmReadings = 0;
    private long lastBpmReadingMillis = 0;
    private BreathingAnalyzer.BreathingResult lastBreathing = BreathingAnalyzer.classify(0);

    private int sessionCoughCount = 0;
    private int totalActuationCount = 0;
    private String aiCoughLabel = "Analyzing respiration pattern...";
    /** Latest cough or wheeze the model heard with enough confidence; null until it hears one. */
    private CoughClass predictedCough = null;
    private int predictedConfidencePercent = 0;
    // Set on the UI thread, read from BLE callbacks
    private volatile boolean sessionEnding = false;

    // ---- Audio-thread state (only touched inside audioExecutor) ----
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private AiCoughClassifier coughClassifier;
    private final ByteArrayOutputStream audioWindow = new ByteArrayOutputStream();
    private boolean lastClipWasCough = false;

    // Baseline Pre-assessment Flags
    private boolean baselineAsthma = false;
    private boolean baselineCOPD = false;
    private boolean baselineCongestion = false;

    private static class PressurePacket {
        final long arrivalMillis;
        final float[] samples;

        PressurePacket(long arrivalMillis, float[] samples) {
            this.arrivalMillis = arrivalMillis;
            this.samples = samples;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Use the selected device, or the last inhaler this phone connected to
        deviceAddress = getIntent().getStringExtra("deviceAddress");
        if (deviceAddress == null || deviceAddress.isEmpty()) {
            deviceAddress = getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getString(PREF_LAST_DEVICE, null);
        }
        if (deviceAddress == null || deviceAddress.isEmpty()) {
            Toast.makeText(this, "Scan and select your TANGHAP inhaler first.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, DeviceScanActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_live_session);

        // Load Baseline Profile from SharedPreferences
        loadBaselineProfile();

        try {
            coughClassifier = new AiCoughClassifier(this);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to load cough model", e);
            aiCoughLabel = "Cough model unavailable";
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        toolbar.setNavigationIconTint(ContextCompat.getColor(this, R.color.text_high_emphasis));
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        tvAiFindings = findViewById(R.id.tvAiFindings);
        tvAiObservations = findViewById(R.id.tvAiObservations);
        tvOtcRecommendation = findViewById(R.id.tvOtcRecommendation);
        layoutOtc = findViewById(R.id.layoutOtc);
        btnNearbyHospitals = findViewById(R.id.btnNearbyHospitals);
        btnCallEmergency = findViewById(R.id.btnCallEmergency);

        chartBreathing = findViewById(R.id.chartBreathingPattern);
        chartCough = findViewById(R.id.chartCough);
        tvBreathingPattern = findViewById(R.id.tvBreathingPattern);
        tvBreathingRate = findViewById(R.id.tvBreathingRate);
        tvLiveEffort = findViewById(R.id.tvLiveEffort);
        tvLiveActuations = findViewById(R.id.tvLiveActuations);

        if (tvAiFindings != null) tvAiFindings.setText(aiCoughLabel);
        if (tvAiObservations != null) tvAiObservations.setText("Waiting for respiratory and acoustic data...");
        if (tvLiveEffort != null) tvLiveEffort.setText("0.0 kPa");
        if (tvBreathingRate != null) tvBreathingRate.setText("-- BPM");
        if (tvBreathingPattern != null) tvBreathingPattern.setText("Measuring breathing rate...");
        if (tvLiveActuations != null) tvLiveActuations.setText("0 Actuations");
        if (layoutOtc != null) layoutOtc.setVisibility(View.GONE);
        if (btnNearbyHospitals != null) btnNearbyHospitals.setVisibility(View.GONE);

        setupCharts();

        btnNearbyHospitals.setOnClickListener(v -> NearbyHospitals.open(this));
        findViewById(R.id.btnEndSession).setOnClickListener(v -> endSessionAndProceed());

        ble = new BleManager(this, this);
        ble.connect(deviceAddress);
    }

    private void loadBaselineProfile() {
        SharedPreferences pref = getSharedPreferences(HealthPrefs.HEALTH_PREFS, Context.MODE_PRIVATE);
        baselineAsthma = pref.getBoolean("has_asthma", false);
        baselineCOPD = pref.getBoolean("has_copd", false);
        baselineCongestion = pref.getBoolean("has_congestion", false);
    }

    // =========================================================
    // END OF SESSION
    // =========================================================

    private void endSessionAndProceed() {
        if (sessionEnding) return;
        sessionEnding = true;

        if (ble != null) {
            ble.disconnect();
        }

        // Let the audio thread post any clip it is still classifying, then finish on the UI thread
        runOnAudioThread(() -> runOnUiThread(this::finishSession));
    }

    private void finishSession() {
        long endMillis = System.currentTimeMillis();

        int avgBpm = bpmReadings > 0 ? Math.round(bpmSum / (float) bpmReadings) : lastBreathing.bpm;
        BreathingAnalyzer.BreathingResult sessionBreathing = BreathingAnalyzer.classify(avgBpm);

        AssessmentEngine.AssessmentResult assessment = assessmentEngine.generatePreliminaryAssessment(
                sessionBreathing, predictedCough, baselineAsthma, baselineCOPD, baselineCongestion);

        String coughFinding = predictedCough != null
                ? predictedCough.findingText(predictedConfidencePercent)
                : CoughClass.NON_COUGH.label;

        inhalationTracker.finish();
        long inhaleMillis = inhalationTracker.getAverageInhaleMillis();

        getSharedPreferences(HealthPrefs.SESSION_PREFS, Context.MODE_PRIVATE).edit()
                .putLong("last_timestamp", endMillis)
                .apply();

        Intent intent = new Intent(LiveSessionActivity.this, SessionResultActivity.class);
        intent.putExtra(SessionResultActivity.EXTRA_START, sessionStartMillis);
        intent.putExtra(SessionResultActivity.EXTRA_END, endMillis);
        intent.putExtra(SessionResultActivity.EXTRA_MAX_EFFORT, maxInhaleEffort);
        intent.putExtra(SessionResultActivity.EXTRA_INHALE_MS, inhaleMillis);
        intent.putExtra(SessionResultActivity.EXTRA_AVG_BPM, avgBpm);
        intent.putExtra(SessionResultActivity.EXTRA_PATTERN, sessionBreathing.pattern);
        intent.putExtra(SessionResultActivity.EXTRA_COUGH_COUNT, sessionCoughCount);
        intent.putExtra(SessionResultActivity.EXTRA_ACTUATIONS, totalActuationCount);
        intent.putExtra(SessionResultActivity.EXTRA_COUGH_FINDING, coughFinding);
        intent.putExtra(SessionResultActivity.EXTRA_INSIGHT, assessment.insight);
        intent.putExtra(SessionResultActivity.EXTRA_OBSERVATIONS, String.join(" • ", assessment.observations));
        intent.putExtra(SessionResultActivity.EXTRA_OTC, assessment.otcCategory);
        intent.putExtra(SessionResultActivity.EXTRA_RISK_HIGH, assessment.recommendHospital);
        intent.putExtra(SessionResultActivity.EXTRA_RECOMMENDATION, assessment.guidance);
        intent.putExtra(SessionResultActivity.EXTRA_TRACE, buildPressureTrace());

        startActivity(intent);
        finish();
    }

    /** Down-sampled recorded pressure, keeping the largest-magnitude value of each bucket so peaks survive. */
    private String buildPressureTrace() {
        if (recordedCount == 0) return null;
        int points = Math.min(TRACE_POINTS, recordedCount);
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < points; p++) {
            int from = (int) ((long) p * recordedCount / points);
            int to = (int) ((long) (p + 1) * recordedCount / points);
            float best = recordedSamples[from];
            for (int i = from + 1; i < to; i++) {
                if (Math.abs(recordedSamples[i]) > Math.abs(best)) best = recordedSamples[i];
            }
            if (p > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%.2f", best));
        }
        return sb.toString();
    }

    // =========================================================
    // CHARTS
    // =========================================================

    private void setupCharts() {
        breathingDataSet = createLineSet("Pressure", ContextCompat.getColor(this, R.color.primary_light));
        breathingDataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        breathingDataSet.setDrawFilled(true);
        breathingDataSet.setFillAlpha(40);

        LineData lineData = new LineData(breathingDataSet);
        chartBreathing.setData(lineData);
        styleChart(chartBreathing);

        coughDataSet = new BarDataSet(new ArrayList<>(), "Cough");
        coughDataSet.setColor(ContextCompat.getColor(this, R.color.error));
        coughDataSet.setDrawValues(false);

        chartCough.setData(new BarData(coughDataSet));
        styleChart(chartCough);
    }

    private LineDataSet createLineSet(String label, int color) {
        LineDataSet set = new LineDataSet(new ArrayList<>(), label);
        set.setColor(color);
        set.setLineWidth(2.5f);
        set.setDrawCircles(false);
        set.setDrawValues(false);
        return set;
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

    // =========================================================
    // PRESSURE (UI thread)
    // =========================================================

    @Override
    public void onPressureSamples(float[] kpaSamples) {
        final long arrival = System.currentTimeMillis();
        runOnUiThread(() -> handlePressurePacket(arrival, kpaSamples));
    }

    private void handlePressurePacket(long arrival, float[] kpaSamples) {
        if (sessionEnding || kpaSamples.length == 0) return;

        inhalationTracker.addSamples(kpaSamples, arrival);

        pressureWindow.addLast(new PressurePacket(arrival, kpaSamples));
        while (pressureWindow.size() > 1 && arrival - pressureWindow.peekFirst().arrivalMillis > BREATH_WINDOW_MS) {
            pressureWindow.removeFirst();
        }

        float currentPeak = 0f;
        for (float val : kpaSamples) {
            // Effort is the magnitude, so it works whether inhalation reads as + or - kPa
            float effort = Math.abs(val);
            if (effort > currentPeak) currentPeak = effort;

            recordSample(val);

            breathingDataSet.addEntry(new Entry(breathingIndex++, val));
            if (breathingDataSet.getEntryCount() > 150) {
                breathingDataSet.removeFirst();
            }
        }

        if (tvLiveEffort != null) {
            tvLiveEffort.setText(String.format(Locale.US, "%.1f kPa", currentPeak));
        }
        if (currentPeak > maxInhaleEffort) maxInhaleEffort = currentPeak;

        if (chartBreathing != null && chartBreathing.getData() != null) {
            chartBreathing.getData().notifyDataChanged();
            chartBreathing.notifyDataSetChanged();
            chartBreathing.setVisibleXRangeMaximum(150);
            chartBreathing.moveViewToX(breathingIndex);
            chartBreathing.invalidate();
        }

        runAiAnalysis();
    }

    private void recordSample(float val) {
        if (recordedCount >= MAX_RECORDED_SAMPLES) return;
        if (recordedCount == recordedSamples.length) {
            recordedSamples = Arrays.copyOf(recordedSamples, Math.min(recordedSamples.length * 2, MAX_RECORDED_SAMPLES));
        }
        recordedSamples[recordedCount++] = val;
    }

    // =========================================================
    // ASSESSMENT (UI thread)
    // =========================================================

    private void runAiAnalysis() {
        // Breathing rate over the real time covered by the window
        int windowSamples = 0;
        for (PressurePacket p : pressureWindow) windowSamples += p.samples.length;
        float[] dataArray = new float[windowSamples];
        int idx = 0;
        for (PressurePacket p : pressureWindow) {
            System.arraycopy(p.samples, 0, dataArray, idx, p.samples.length);
            idx += p.samples.length;
        }
        float windowSec = 0f;
        int windowPackets = pressureWindow.size();
        if (windowPackets >= 2) {
            long span = pressureWindow.peekLast().arrivalMillis - pressureWindow.peekFirst().arrivalMillis;
            windowSec = span / 1000f * windowPackets / (windowPackets - 1);
        }

        BreathingAnalyzer.BreathingResult br = breathingAnalyzer.analyze(dataArray, windowSec);
        lastBreathing = br;

        if (tvBreathingRate != null) tvBreathingRate.setText(br.bpm > 0 ? br.bpm + " BPM" : "-- BPM");
        if (tvBreathingPattern != null) tvBreathingPattern.setText(br.pattern);

        long now = System.currentTimeMillis();
        if (br.bpm > 0 && now - lastBpmReadingMillis >= BPM_SAMPLE_INTERVAL_MS) {
            lastBpmReadingMillis = now;
            bpmSum += br.bpm;
            bpmReadings++;
        }

        AssessmentEngine.AssessmentResult assessment = assessmentEngine.generatePreliminaryAssessment(
                br, predictedCough, baselineAsthma, baselineCOPD, baselineCongestion);

        if (tvAiFindings != null) {
            tvAiFindings.setText(aiCoughLabel);
        }

        boolean hasPredicted = predictedCough != null;
        if (tvAiObservations != null) {
            if (!hasPredicted) {
                tvAiObservations.setText("Processing acoustic spectral data and respiratory patterns...");
            } else {
                tvAiObservations.setText(String.join(" • ", assessment.observations));
            }
        }

        if (hasPredicted && layoutOtc != null && tvOtcRecommendation != null) {
            layoutOtc.setVisibility(View.VISIBLE);
            tvOtcRecommendation.setText(assessment.otcCategory);
        }

        boolean worsening = hasPredicted && assessment.recommendHospital;
        if (worsening && btnNearbyHospitals != null) {
            btnNearbyHospitals.setVisibility(View.VISIBLE);
        } else if (btnNearbyHospitals != null) {
            btnNearbyHospitals.setVisibility(View.GONE);
        }
        EmergencyContactButton.bind(this, btnCallEmergency, worsening);

        if (worsening && !worseningNotified) {
            worseningNotified = true;
            NotificationHelper.show(this, NotificationHelper.ID_WORSENING,
                    "Worsening respiratory pattern",
                    assessment.possibleFindings.isEmpty()
                            ? "Readings suggest a worsening condition. Open TANGHAP for guidance."
                            : assessment.possibleFindings.get(0) + " Open TANGHAP for guidance.");
        }
    }

    private void onClipClassified(AiCoughClassifier.Result result, boolean newCough) {
        if (sessionEnding && isFinishing()) return;
        boolean accepted = result.isConfident();
        addCoughBar(accepted && result.type.isCough ? result.percent() : 0);
        if (newCough) sessionCoughCount++;
        // Unsure clips (below MIN_CONFIDENCE) and non-cough sounds keep the last result
        if (accepted && result.type != CoughClass.NON_COUGH) {
            predictedCough = result.type;
            predictedConfidencePercent = result.percent();
            aiCoughLabel = result.type.displayText(predictedConfidencePercent);
        }
        runAiAnalysis();
    }

    // =========================================================
    // AUDIO + COUGH MODEL (audio thread)
    // =========================================================

    @Override
    public void onCoughEvent(long tsMillis, int intensity) {}

    @Override
    public void onAudioDataReceived(byte[] audioBytes) {
        final byte[] copy = audioBytes.clone();
        runOnAudioThread(() -> processAudio(copy));
    }

    private void runOnAudioThread(Runnable r) {
        try {
            audioExecutor.execute(r);
        } catch (RejectedExecutionException ignored) {
            // Activity destroyed; drop late BLE data
        }
    }

    /** Classifies every 2-second window of the stream, stepping 1 second at a time. */
    private void processAudio(byte[] audioBytes) {
        if (coughClassifier == null) return;
        audioWindow.write(audioBytes, 0, audioBytes.length);

        while (audioWindow.size() >= CLIP_BYTES) {
            byte[] buffered = audioWindow.toByteArray();
            byte[] clip = Arrays.copyOf(buffered, CLIP_BYTES);
            audioWindow.reset();
            audioWindow.write(buffered, CLIP_HOP_BYTES, buffered.length - CLIP_HOP_BYTES);

            AiCoughClassifier.Result result;
            try {
                result = coughClassifier.classify(normalizePcmAudio(clip));
            } catch (RuntimeException e) {
                Log.e(TAG, "Cough model failed on an audio clip", e);
                continue;
            }

            // Overlapping windows hear the same cough twice; count it once
            boolean confidentCough = result.isConfident() && result.type.isCough;
            boolean newCough = confidentCough && !lastClipWasCough;
            lastClipWasCough = confidentCough;
            runOnUiThread(() -> onClipClassified(result, newCough));
        }
    }

    private void addCoughBar(int value) {
        coughEntries.add(new BarEntry(coughEntries.size(), value));

        if (coughEntries.size() > 30) {
            coughEntries.remove(0);
            for (int i = 0; i < coughEntries.size(); i++) {
                coughEntries.get(i).setX(i);
            }
        }

        coughDataSet.setValues(coughEntries);
        chartCough.getData().notifyDataChanged();
        chartCough.notifyDataSetChanged();
        chartCough.invalidate();
    }

    private byte[] normalizePcmAudio(byte[] pcmData) {
        if (pcmData == null || pcmData.length < 2) return pcmData;

        int maxPeak = 0;
        short[] samples = new short[pcmData.length / 2];

        for (int i = 0; i < samples.length; i++) {
            short s = (short) ((pcmData[i * 2 + 1] << 8) | (pcmData[i * 2] & 0xFF));
            samples[i] = s;
            int absS = Math.abs(s);
            if (absS > maxPeak) maxPeak = absS;
        }

        if (maxPeak <= 0 || maxPeak >= 28000) {
            return pcmData;
        }

        float scale = 24000.0f / maxPeak;
        byte[] normalized = new byte[pcmData.length];

        for (int i = 0; i < samples.length; i++) {
            int scaled = Math.round(samples[i] * scale);
            if (scaled > 32767) scaled = 32767;
            if (scaled < -32768) scaled = -32768;

            short finalSample = (short) scaled;
            normalized[i * 2] = (byte) (finalSample & 0xFF);
            normalized[i * 2 + 1] = (byte) ((finalSample >> 8) & 0xFF);
        }

        return normalized;
    }

    // =========================================================
    // OTHER BLE EVENTS
    // =========================================================

    @Override
    public void onActuationEvent(long tsMillis) {
        runOnUiThread(() -> {
            if (sessionEnding) return;
            totalActuationCount++;
            if (tvLiveActuations != null) {
                tvLiveActuations.setText(totalActuationCount + " Actuations");
            }
            Toast.makeText(LiveSessionActivity.this, "Inhaler Press Detected! Total: " + totalActuationCount, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onConnected(String deviceName) {
        getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
                .putString(PREF_LAST_DEVICE, deviceAddress)
                .apply();
        mainHandler.post(() -> {
            boolean wasReconnecting = reconnectAttempts > 0;
            reconnectAttempts = 0;
            Toast.makeText(this,
                    wasReconnecting ? "Reconnected to TANGHAP" : "Connected to TANGHAP",
                    Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDisconnected() {
        if (sessionEnding) return;
        final long droppedAt = System.currentTimeMillis();
        mainHandler.post(() -> {
            if (sessionEnding) return;
            if (reconnectAttempts == 0) {
                // First drop only; failed reconnect attempts also land here and should not repeat the alert.
                ConnectionLog.recordDisconnect(this, droppedAt);
                NotificationHelper.show(this, NotificationHelper.ID_CONNECTION,
                        "TANGHAP device disconnected",
                        "The inhaler attachment lost its Bluetooth connection. Keep your phone within range; the app will try to reconnect.");
            }
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                Toast.makeText(this, "Could not reconnect. Please scan for the device again.", Toast.LENGTH_LONG).show();
                return;
            }
            reconnectAttempts++;
            Toast.makeText(this, "Device disconnected. Reconnecting (attempt " + reconnectAttempts
                    + " of " + MAX_RECONNECT_ATTEMPTS + ")...", Toast.LENGTH_SHORT).show();
            mainHandler.postDelayed(() -> {
                if (!sessionEnding && ble != null) ble.connect(deviceAddress);
            }, RECONNECT_DELAY_MS);
        });
    }

    @Override public void onScanResults(List<BleDeviceItem> results) {}

    @Override
    public void onBattery(Integer percent) {
        if (percent != null) {
            SharedPreferences pref = getSharedPreferences(HealthPrefs.SESSION_PREFS, Context.MODE_PRIVATE);
            pref.edit().putInt("last_battery", percent).apply();

            runOnUiThread(() -> {
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setSubtitle(" 🔋 " + percent + "%");
                }
            });
        }
    }

    @Override public void onDeviceInfo(String info) {}

    @Override
    public void onError(String message) {
        Log.e(TAG, "BLE error: " + message);
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sessionEnding = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (ble != null) {
            ble.disconnect();
        }
        runOnAudioThread(() -> {
            if (coughClassifier != null) coughClassifier.close();
        });
        audioExecutor.shutdown();
    }
}
