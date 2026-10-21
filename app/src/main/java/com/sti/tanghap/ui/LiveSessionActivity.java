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
import com.sti.tanghap.BuildConfig;
import com.sti.tanghap.R;
import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.ai.BreathingAnalyzer;
import com.sti.tanghap.ble.ble.BleDeviceItem;
import com.sti.tanghap.ble.ble.BleListener;
import com.sti.tanghap.ble.ble.BleManager;
import com.sti.tanghap.data.ConnectionLog;
import com.sti.tanghap.data.HealthPrefs;
import com.sti.tanghap.domain.CoughAnalyzer;
import com.sti.tanghap.domain.InhalationTracker;
import com.sti.tanghap.notify.NotificationHelper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LiveSessionActivity extends AppCompatActivity implements BleListener {
    private static final String TAG = "LiveSessionActivity";

    /** Flask server URL; set with -Ptanghap.flaskUrl=... or in gradle.properties. */
    public static final String BASE_URL = BuildConfig.FLASK_BASE_URL;

    /** app_prefs key for the last inhaler this phone connected to (device setting, not user data). */
    public static final String PREF_LAST_DEVICE = "last_device_address";

    // Pressure analysis
    private static final long BREATH_WINDOW_MS = 30_000;      // BPM is measured over the last 30 s
    private static final long BPM_SAMPLE_INTERVAL_MS = 5_000; // how often a BPM reading enters the session average
    private static final int MAX_RECORDED_SAMPLES = 200_000;  // memory cap for the stored session trace
    private static final int TRACE_POINTS = 200;              // points saved for the result chart

    // Cough episode detection (audio)
    private static final int AUDIO_THRESHOLD = 3800;          // above background static hum (~1500 - 3500)
    private static final long COUGH_END_GAP_MS = 250;         // quiet time that ends one cough episode
    private static final long COUGH_MAX_MS = 1500;            // longer loud sounds are not one cough
    private static final long RECENT_COUGH_WINDOW_MS = 60_000;
    private static final int AUDIO_SAMPLE_RATE = 16000;

    // Corrected Audio Buffering Constants
    private static final int AUDIO_CHUNK_BYTES = AUDIO_SAMPLE_RATE * 2 * 2; // 2 seconds at 16kHz 16-bit
    private static final int PRE_ROLL_BYTES = AUDIO_SAMPLE_RATE * 2 / 2;    // 0.5 seconds pre-roll

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
    private final CoughAnalyzer coughAnalyzer = new CoughAnalyzer();
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

    private final List<CoughAnalyzer.CoughFeatures> sessionCoughs = new ArrayList<>();
    private int totalActuationCount = 0;
    private String aiCoughLabel = "Analyzing respiration pattern...";
    private String predictedCoughLabel = null;
    // Set on the UI thread, read from BLE callbacks
    private volatile boolean sessionEnding = false;

    // Written on the UI thread, read on the audio thread
    private volatile boolean hasPredicted = false;

    // ---- Audio-thread state (only touched inside audioExecutor) ----
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private final ByteArrayOutputStream pcmAudioBuffer = new ByteArrayOutputStream();
    private final ByteArrayOutputStream preRollBuffer = new ByteArrayOutputStream();
    private boolean isRecordingCough = false;
    private boolean isSendingToFlask = false;
    private boolean inCoughEpisode = false;
    private boolean waitingForQuiet = false;
    private long coughStartMillis = 0;
    private long lastLoudMillis = 0;
    private int coughPeakAmplitude = 0;
    private long lastPacketMs = 0;
    private long lastGraphUpdateTime = 0;

    // Baseline Pre-assessment Flags
    private boolean baselineAsthma = false;
    private boolean baselineCOPD = false;
    private boolean baselineCongestion = false;

    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

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

        // Close any cough episode still open on the audio thread, then finish on the UI thread
        runOnAudioThread(() -> {
            flushCoughEpisode(System.currentTimeMillis());
            runOnUiThread(this::finishSession);
        });
    }

    private void finishSession() {
        long endMillis = System.currentTimeMillis();

        int avgBpm = bpmReadings > 0 ? Math.round(bpmSum / (float) bpmReadings) : lastBreathing.bpm;
        BreathingAnalyzer.BreathingResult sessionBreathing = BreathingAnalyzer.classify(avgBpm);

        List<CoughAnalyzer.CoughFeatures> busiestMinute = busiestCoughWindow(sessionCoughs, RECENT_COUGH_WINDOW_MS);
        CoughAnalyzer.CoughType coughType = coughAnalyzer.analyze(busiestMinute, maxInhaleEffort);
        AssessmentEngine.AssessmentResult assessment = assessmentEngine.generatePreliminaryAssessment(
                sessionBreathing, coughType, predictedCoughLabel, busiestMinute.size(),
                baselineAsthma, baselineCOPD, baselineCongestion);

        String coughFinding = predictedCoughLabel != null
                ? predictedCoughLabel
                : (sessionCoughs.isEmpty() ? CoughAnalyzer.CoughType.NORMAL.label : coughType.label);

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
        intent.putExtra(SessionResultActivity.EXTRA_COUGH_COUNT, sessionCoughs.size());
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

    /** The 60-second stretch of the session with the most cough episodes. */
    private static List<CoughAnalyzer.CoughFeatures> busiestCoughWindow(List<CoughAnalyzer.CoughFeatures> coughs, long windowMs) {
        int bestStart = 0, bestEnd = 0, start = 0;
        for (int end = 0; end < coughs.size(); end++) {
            while (coughs.get(end).timestamp - coughs.get(start).timestamp > windowMs) start++;
            if (end - start + 1 > bestEnd - bestStart) {
                bestStart = start;
                bestEnd = end + 1;
            }
        }
        return new ArrayList<>(coughs.subList(bestStart, bestEnd));
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

        List<CoughAnalyzer.CoughFeatures> recentCoughs = recentCoughs(now);
        CoughAnalyzer.CoughType ct = coughAnalyzer.analyze(recentCoughs, maxInhaleEffort);

        AssessmentEngine.AssessmentResult assessment = assessmentEngine.generatePreliminaryAssessment(
                br, ct, predictedCoughLabel, recentCoughs.size(), baselineAsthma, baselineCOPD, baselineCongestion);

        if (tvAiFindings != null) {
            tvAiFindings.setText(aiCoughLabel);
        }

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

    private List<CoughAnalyzer.CoughFeatures> recentCoughs(long now) {
        List<CoughAnalyzer.CoughFeatures> recent = new ArrayList<>();
        for (CoughAnalyzer.CoughFeatures c : sessionCoughs) {
            if (now - c.timestamp <= RECENT_COUGH_WINDOW_MS) recent.add(c);
        }
        return recent;
    }

    private void onCoughEpisode(CoughAnalyzer.CoughFeatures cough) {
        if (sessionEnding && isFinishing()) return;
        sessionCoughs.add(cough);
        runAiAnalysis();
    }

    // =========================================================
    // AUDIO (audio thread)
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

    private void processAudio(byte[] audioBytes) {
        try {
            int maxAmplitude = 0;
            for (int i = 0; i < audioBytes.length - 1; i += 2) {
                short sample = (short) ((audioBytes[i + 1] << 8) | (audioBytes[i] & 0xFF));
                int absSample = Math.abs(sample);
                if (absSample > maxAmplitude) {
                    maxAmplitude = absSample;
                }
            }

            long now = System.currentTimeMillis();
            boolean loud = maxAmplitude > AUDIO_THRESHOLD;

            detectCoughEpisode(now, loud, maxAmplitude, audioBytes.length);

            if (loud && now - lastGraphUpdateTime > 120) {
                // Throttle graph updates to every 120ms to prevent erratic redraws
                lastGraphUpdateTime = now;
                final int currentVolume = maxAmplitude;
                runOnUiThread(() -> addCoughBar(currentVolume));
            }

            // ---- 2-second clip for the audio classifier ----
            if (!isRecordingCough) {
                preRollBuffer.write(audioBytes);
                if (preRollBuffer.size() > PRE_ROLL_BYTES) {
                    byte[] currentPreRoll = preRollBuffer.toByteArray();
                    preRollBuffer.reset();
                    int keepOffset = currentPreRoll.length - PRE_ROLL_BYTES;
                    preRollBuffer.write(currentPreRoll, keepOffset, PRE_ROLL_BYTES);
                }
            }

            if (loud && !hasPredicted && !isRecordingCough && !isSendingToFlask) {
                // The pre-roll already contains this packet
                isRecordingCough = true;
                pcmAudioBuffer.reset();
                pcmAudioBuffer.write(preRollBuffer.toByteArray());
                preRollBuffer.reset();

                runOnUiThread(() -> {
                    aiCoughLabel = "Cough Detected: Analyzing Audio...";
                    runAiAnalysis();
                });
            } else if (isRecordingCough) {
                pcmAudioBuffer.write(audioBytes);
            }

            if (isRecordingCough && pcmAudioBuffer.size() >= AUDIO_CHUNK_BYTES) {
                isRecordingCough = false;
                isSendingToFlask = true;

                byte[] rawPcm = pcmAudioBuffer.toByteArray();
                pcmAudioBuffer.reset();
                byte[] normalizedPcm = normalizePcmAudio(rawPcm);

                File tempWav = new File(getCacheDir(), "ble_cough_chunk.wav");
                savePcmAsWav(normalizedPcm, tempWav, AUDIO_SAMPLE_RATE, 1, 16);

                sendAudioToFlaskServer(tempWav);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error buffering BLE audio data", e);
            isRecordingCough = false;
            isSendingToFlask = false;
        }
    }

    /**
     * One cough = one continuous loud burst. It ends after {@link #COUGH_END_GAP_MS} of quiet.
     * Sounds louder than the threshold for longer than {@link #COUGH_MAX_MS} (e.g. talking) are
     * counted once and must go quiet before another cough can start.
     */
    private void detectCoughEpisode(long now, boolean loud, int amplitude, int packetBytes) {
        if (waitingForQuiet) {
            if (!loud && now - lastLoudMillis > COUGH_END_GAP_MS) waitingForQuiet = false;
            if (loud) lastLoudMillis = now;
            return;
        }

        if (loud) {
            if (!inCoughEpisode) {
                inCoughEpisode = true;
                coughStartMillis = now;
                coughPeakAmplitude = amplitude;
            } else {
                coughPeakAmplitude = Math.max(coughPeakAmplitude, amplitude);
            }
            lastLoudMillis = now;
            lastPacketMs = packetBytes * 1000L / (AUDIO_SAMPLE_RATE * 2L);
        }

        if (inCoughEpisode) {
            if (now - coughStartMillis > COUGH_MAX_MS) {
                flushCoughEpisode(now);
                waitingForQuiet = true;
            } else if (!loud && now - lastLoudMillis > COUGH_END_GAP_MS) {
                flushCoughEpisode(now);
            }
        }
    }

    private void flushCoughEpisode(long now) {
        if (!inCoughEpisode) return;
        inCoughEpisode = false;

        long durationMs = Math.min(lastLoudMillis - coughStartMillis + lastPacketMs, COUGH_MAX_MS);
        // Map 16-bit amplitude onto the 0-255 intensity scale used by CoughAnalyzer and the dataset
        int intensity = Math.min(255, Math.round(coughPeakAmplitude * 255f / 32767f));
        long startedAt = coughStartMillis;

        CoughAnalyzer.CoughFeatures cough = new CoughAnalyzer.CoughFeatures(intensity, durationMs, false, startedAt);
        runOnUiThread(() -> onCoughEpisode(cough));
    }

    private void addCoughBar(int volume) {
        coughEntries.add(new BarEntry(coughEntries.size(), volume));

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
    // AUDIO CLASSIFIER (trained model on the Flask server)
    // =========================================================

    private void sendAudioToFlaskServer(File audioFile) {
        RequestBody fileBody = RequestBody.create(audioFile, MediaType.parse("audio/wav"));

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio", audioFile.getName(), fileBody)
                .build();

        Request request = new Request.Builder()
                .url(BASE_URL + "predict")
                .post(requestBody)
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to connect to Local Flask API: " + e.getMessage());
                finishClassifierRequest();
                runOnUiThread(() -> resetCoughLabel());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String rawPrediction = "";
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        String jsonResponse = response.body().string();
                        Log.d(TAG, "Flask API Response: " + jsonResponse);
                        JSONObject json = new JSONObject(jsonResponse);
                        rawPrediction = json.optString("prediction",
                                json.optString("cough_type",
                                        json.optString("result", ""))).toLowerCase(Locale.US).trim();
                    } else {
                        Log.e(TAG, "Flask API error: HTTP " + response.code());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "JSON Parsing error", e);
                } finally {
                    response.close();
                    finishClassifierRequest();
                }

                final String prediction = rawPrediction;
                runOnUiThread(() -> applyServerPrediction(prediction));
            }
        });
    }

    private void finishClassifierRequest() {
        runOnAudioThread(() -> {
            isSendingToFlask = false;
            isRecordingCough = false;
        });
    }

    private void applyServerPrediction(String rawPrediction) {
        if (rawPrediction.isEmpty()
                || rawPrediction.contains("non_cough") || rawPrediction.contains("non cough")
                || rawPrediction.contains("noise") || rawPrediction.contains("background")) {
            resetCoughLabel();
            return;
        }
        String formattedLabel = rawPrediction.replace("_", " ");
        formattedLabel = formattedLabel.substring(0, 1).toUpperCase(Locale.US) + formattedLabel.substring(1);
        String displayText = rawPrediction.contains("wheez")
                ? "Wheezing Detected"
                : "Cough Detected: " + formattedLabel;
        setPrediction(formattedLabel, displayText);
    }

    /** Model unreachable, errored, or heard no cough: wait for the next cough clip. */
    private void resetCoughLabel() {
        aiCoughLabel = "Analyzing respiration pattern...";
        runAiAnalysis();
    }

    private void setPrediction(String label, String displayText) {
        predictedCoughLabel = label;
        aiCoughLabel = displayText;
        hasPredicted = true;
        runAiAnalysis();
    }

    private void savePcmAsWav(byte[] pcmData, File wavFile, int sampleRate, int channels, int bitDepth) throws IOException {
        long totalAudioLen = pcmData.length;
        long totalDataLen = totalAudioLen + 36;
        long byteRate = (long) sampleRate * channels * bitDepth / 8;

        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0;
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * bitDepth / 8); header[33] = 0;
        header[34] = (byte) bitDepth; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

        try (FileOutputStream out = new FileOutputStream(wavFile)) {
            out.write(header);
            out.write(pcmData);
        }
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
        audioExecutor.shutdown();
    }
}
