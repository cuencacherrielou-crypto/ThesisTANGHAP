package com.sti.tanghap.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
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
import com.sti.tanghap.ble.ble.BleDeviceItem;
import com.sti.tanghap.ble.ble.BleListener;
import com.sti.tanghap.ble.ble.BleManager;
import com.sti.tanghap.data.ConnectionLog;
import com.sti.tanghap.domain.CoughAnalyzer;
import com.sti.tanghap.domain.InhalationTracker;
import com.sti.tanghap.notify.NotificationHelper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    public static final String BASE_URL = "http://192.168.1.5:5000/";

    private BleManager ble;
    private LineChart chartBreathing;
    private BarChart chartCough;
    private TextView tvBreathingPattern, tvBreathingRate, tvLiveEffort, tvLiveActuations;

    private TextView tvAiFindings, tvAiObservations, tvOtcRecommendation;
    private View layoutOtc;
    private MaterialButton btnNearbyHospitals;
    private MaterialButton btnCallEmergency;
    private AiCoughClassifier aiCoughClassifier;
    private final BreathingAnalyzer breathingAnalyzer = new BreathingAnalyzer();
    private final AssessmentEngine assessmentEngine = new AssessmentEngine();
    private final CoughAnalyzer coughAnalyzer = new CoughAnalyzer();
    private final InhalationTracker inhalationTracker = new InhalationTracker();
    private AssessmentEngine.AssessmentResult lastAssessment;
    private String lastOtcLabel;
    private long sessionStartMillis;
    private boolean sessionEndedByUser = false;
    private boolean worseningNotified = false;
    private String deviceAddress;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 3000;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private LineDataSet breathingDataSet;
    private BarDataSet coughDataSet;
    private int breathingIndex = 0;
    private final List<BarEntry> coughEntries = new ArrayList<>();
    private final List<CoughAnalyzer.CoughFeatures> recentCoughs = new ArrayList<>();

    private final List<Float> sessionPressureData = new ArrayList<>();
    private float maxInhaleEffort = 0.0f;
    private String aiCoughLabel = "Analyzing respiration pattern...";

    private int totalActuationCount = 0;

    private final ByteArrayOutputStream pcmAudioBuffer = new ByteArrayOutputStream();
    private final ByteArrayOutputStream preRollBuffer = new ByteArrayOutputStream();

    // Corrected Audio Buffering Constants
    private static final int AUDIO_CHUNK_BYTES = 16000 * 2 * 2; // 2 seconds at 16kHz 16-bit
    private static final int PRE_ROLL_BYTES = 16000 * 2 / 2;    // 0.5 seconds pre-roll

    // Debounce / throttle timestamp for smooth graph updating
    private long lastGraphUpdateTime = 0;

    private boolean isRecordingCough = false;
    private boolean isSendingToFlask = false;
    private boolean hasPredicted = false;

    // Baseline Pre-assessment Flags
    private boolean baselineAsthma = false;
    private boolean baselineCOPD = false;
    private boolean baselineBronchitis = false;
    private boolean baselineCongestion = false;

    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_session);

        // Load Baseline Profile from SharedPreferences
        loadBaselineProfile();

        // Clear previous state variables
        hasPredicted = false;
        isRecordingCough = false;
        isSendingToFlask = false;
        maxInhaleEffort = 0.0f;
        totalActuationCount = 0;
        recentCoughs.clear();
        sessionPressureData.clear();
        coughEntries.clear();
        aiCoughLabel = "Analyzing respiration pattern...";
        sessionStartMillis = System.currentTimeMillis();

        try {
            aiCoughClassifier = new AiCoughClassifier(this);
        } catch (IOException e) {
            Log.e(TAG, "Failed to load cough dataset", e);
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
        if (tvBreathingRate != null) tvBreathingRate.setText("0 BPM");
        if (tvBreathingPattern != null) tvBreathingPattern.setText("Normal Breathing");
        if (tvLiveActuations != null) tvLiveActuations.setText("0 Actuations");
        if (layoutOtc != null) layoutOtc.setVisibility(View.GONE);
        if (btnNearbyHospitals != null) btnNearbyHospitals.setVisibility(View.GONE);

        setupCharts();

        btnNearbyHospitals.setOnClickListener(v -> findNearbyHospitals());
        findViewById(R.id.btnEndSession).setOnClickListener(v -> endSessionAndProceed());

        ble = new BleManager(this, this);

        deviceAddress = getIntent().getStringExtra("deviceAddress");
        if (deviceAddress == null || deviceAddress.isEmpty()) {
            deviceAddress = "E0:72:A1:6F:E7:CE";
        }

        ble.connect(deviceAddress);
    }

    private void loadBaselineProfile() {
        SharedPreferences pref = getSharedPreferences("TanghapUserPreferences", Context.MODE_PRIVATE);
        baselineAsthma = pref.getBoolean("has_asthma", false);
        baselineCOPD = pref.getBoolean("has_copd", false);
        baselineBronchitis = pref.getBoolean("has_bronchitis", false);
        baselineCongestion = pref.getBoolean("has_congestion", false);
    }

    private void endSessionAndProceed() {
        sessionEndedByUser = true;
        if (ble != null) {
            ble.disconnect();
        }
        inhalationTracker.finish();

        saveSessionToPreferences();

        Intent intent = new Intent(LiveSessionActivity.this, SessionResultActivity.class);
        intent.putExtra("maxEffort", maxInhaleEffort);
        intent.putExtra("breathingRate", tvBreathingRate != null ? tvBreathingRate.getText().toString() : "N/A");
        intent.putExtra("breathingPattern", tvBreathingPattern != null ? tvBreathingPattern.getText().toString() : "N/A");
        intent.putExtra("aiFindings", tvAiFindings != null ? tvAiFindings.getText().toString() : "N/A");
        intent.putExtra("aiObservations", tvAiObservations != null ? tvAiObservations.getText().toString() : "N/A");
        intent.putExtra("actuationCount", totalActuationCount);
        intent.putExtra("coughCount", recentCoughs.size());
        intent.putExtra("startTimeMillis", sessionStartMillis);
        intent.putExtra("endTimeMillis", System.currentTimeMillis());
        intent.putExtra("inhaleDurationMillis", inhalationTracker.getAverageInhaleMillis());
        intent.putExtra("riskLevelHigh", hasPredicted && lastAssessment != null && lastAssessment.recommendHospital);
        if (hasPredicted && lastOtcLabel != null) intent.putExtra("otcRecommendation", lastOtcLabel);

        startActivity(intent);
        finish();
    }

    private void saveSessionToPreferences() {
        SharedPreferences pref = getSharedPreferences("TanghapSessionHistory", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();

        editor.putFloat("last_max_effort", maxInhaleEffort);
        editor.putString("last_bpm", tvBreathingRate != null ? tvBreathingRate.getText().toString() : "0 BPM");
        editor.putString("last_pattern", tvBreathingPattern != null ? tvBreathingPattern.getText().toString() : "Normal");
        editor.putString("last_findings", tvAiFindings != null ? tvAiFindings.getText().toString() : "No findings");
        editor.putInt("last_actuations", totalActuationCount);
        editor.putLong("last_timestamp", System.currentTimeMillis());

        editor.apply();
    }

    private void setupCharts() {
        breathingDataSet = createLineSet("Pressure", ContextCompat.getColor(this, R.color.primary_light));
        breathingDataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        breathingDataSet.setDrawFilled(true);
        breathingDataSet.setFillAlpha(40);

        LineData lineData = new LineData(breathingDataSet);
        chartBreathing.setData(lineData);
        styleChart(chartBreathing);

        chartBreathing.getAxisLeft().setAxisMinimum(-20.0f);
        chartBreathing.getAxisLeft().setAxisMaximum(5.0f);

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

    @Override
    public void onPressureSamples(float[] kpaSamples) {
        final long arrival = System.currentTimeMillis();
        runOnUiThread(() -> {
            inhalationTracker.addSamples(kpaSamples, arrival);
            float currentPeak = 0;

            for (float val : kpaSamples) {
                sessionPressureData.add(val);
                if (val > currentPeak) currentPeak = val;

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

            if (sessionPressureData.size() >= 20) {
                runAiAnalysis();
            }
        });
    }

    private void runAiAnalysis() {
        float[] dataArray = new float[sessionPressureData.size()];
        for (int i = 0; i < sessionPressureData.size(); i++) dataArray[i] = sessionPressureData.get(i);

        BreathingAnalyzer.BreathingResult br = breathingAnalyzer.analyze(dataArray, 5);

        if (tvBreathingRate != null) tvBreathingRate.setText(br.bpm + " BPM");
        if (tvBreathingPattern != null) tvBreathingPattern.setText(br.pattern);

        CoughAnalyzer.CoughType ct = coughAnalyzer.analyze(recentCoughs, maxInhaleEffort);

        String conditionIndication;
        String labelLower = aiCoughLabel.toLowerCase();

        if (!hasPredicted) {
            conditionIndication = "Standard Respiratory Evaluation";
        } else {
            if (labelLower.contains("wheez")) {
                conditionIndication = "Early Detection of Asthma";
            } else if (labelLower.contains("persistent")) {
                conditionIndication = baselineCOPD ? "COPD Assessment" : "Bronchitis Pattern Recognition";
            } else if (labelLower.contains("wet") || ct == CoughAnalyzer.CoughType.WET) {
                conditionIndication = "Bronchitis Pattern Recognition";
            } else if (labelLower.contains("dry") || ct == CoughAnalyzer.CoughType.DRY) {
                conditionIndication = "Early Detection of Asthma";
            } else if (labelLower.contains("severe") || br.bpm > 24) {
                conditionIndication = "Pneumonia Symptoms Indication";
            } else {
                conditionIndication = "Standard Respiratory Evaluation";
            }
        }

        AssessmentEngine.AssessmentResult assessment = assessmentEngine.generatePreliminaryAssessment(
                br, ct, hasPredicted ? aiCoughLabel : null, baselineAsthma, baselineCOPD, baselineCongestion);
        lastAssessment = assessment;
        if (hasPredicted) lastOtcLabel = conditionIndication;

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
            tvOtcRecommendation.setText(conditionIndication);
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

        if (sessionPressureData.size() > 500) sessionPressureData.subList(0, 200).clear();
    }

    @Override
    public void onCoughEvent(long tsMillis, int intensity) {}

    public void onAudioDataReceived(byte[] audioBytes) {
        try {
            int maxAmplitude = 0;

            for (int i = 0; i < audioBytes.length - 1; i += 2) {
                short sample = (short) ((audioBytes[i + 1] << 8) | (audioBytes[i] & 0xFF));
                int absSample = Math.abs(sample);
                if (absSample > maxAmplitude) {
                    maxAmplitude = absSample;
                }
            }

            // Adjusted threshold to bypass background static hum (~1500 - 3500)
            final int AUDIO_THRESHOLD = 3800;

            if (!isRecordingCough) {
                preRollBuffer.write(audioBytes);
                if (preRollBuffer.size() > PRE_ROLL_BYTES) {
                    byte[] currentPreRoll = preRollBuffer.toByteArray();
                    preRollBuffer.reset();
                    int keepOffset = currentPreRoll.length - PRE_ROLL_BYTES;
                    preRollBuffer.write(currentPreRoll, keepOffset, PRE_ROLL_BYTES);
                }
            }

            if (maxAmplitude > AUDIO_THRESHOLD) {
                long now = System.currentTimeMillis();

                // Throttle graph updates to every 120ms to prevent erratic redraws
                if (now - lastGraphUpdateTime > 120) {
                    lastGraphUpdateTime = now;

                    final int currentVolume = maxAmplitude;
                    runOnUiThread(() -> {
                        coughEntries.add(new BarEntry(coughEntries.size(), currentVolume));

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

                        recentCoughs.add(new CoughAnalyzer.CoughFeatures(currentVolume, 800, false, System.currentTimeMillis()));
                        if (recentCoughs.size() > 10) recentCoughs.remove(0);

                        if (!hasPredicted && !isRecordingCough && !isSendingToFlask) {
                            isRecordingCough = true;
                            pcmAudioBuffer.reset();
                            try {
                                pcmAudioBuffer.write(preRollBuffer.toByteArray());
                            } catch (IOException ignored) {}
                            preRollBuffer.reset();

                            aiCoughLabel = "Cough Detected: Analyzing Audio...";
                            runAiAnalysis();
                        }
                    });
                }
            }

            if (!hasPredicted && isRecordingCough && !isSendingToFlask) {
                pcmAudioBuffer.write(audioBytes);

                if (pcmAudioBuffer.size() >= AUDIO_CHUNK_BYTES) {
                    isRecordingCough = false;
                    isSendingToFlask = true;

                    byte[] rawPcm = pcmAudioBuffer.toByteArray();
                    pcmAudioBuffer.reset();
                    byte[] normalizedPcm = normalizePcmAudio(rawPcm);

                    runOnUiThread(() -> {
                        aiCoughLabel = "Cough Detected: Analyzing Audio...";
                        runAiAnalysis();
                    });

                    File tempWav = new File(getCacheDir(), "ble_cough_chunk.wav");
                    savePcmAsWav(normalizedPcm, tempWav, 16000, 1, 16);

                    sendAudioToFlaskServer(tempWav);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error buffering BLE audio data", e);
            isRecordingCough = false;
            isSendingToFlask = false;
        }
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
                isSendingToFlask = false;
                isRecordingCough = false;

                runOnUiThread(() -> {
                    aiCoughLabel = "Analyzing respiration pattern...";
                    runAiAnalysis();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                isSendingToFlask = false;
                isRecordingCough = false;

                if (response.isSuccessful() && response.body() != null) {
                    String jsonResponse = response.body().string();
                    Log.d(TAG, "Flask API Response: " + jsonResponse);

                    try {
                        JSONObject json = new JSONObject(jsonResponse);

                        String rawPrediction = json.optString("prediction",
                                json.optString("cough_type",
                                        json.optString("result", ""))).toLowerCase().trim();

                        if (!rawPrediction.isEmpty()) {
                            if (rawPrediction.contains("non_cough") || rawPrediction.contains("non cough") || rawPrediction.contains("noise") || rawPrediction.contains("background")) {
                                runOnUiThread(() -> {
                                    aiCoughLabel = "Analyzing respiration pattern...";
                                    runAiAnalysis();
                                });
                            } else {
                                String formattedLabel = rawPrediction.replace("_", " ");
                                formattedLabel = formattedLabel.substring(0, 1).toUpperCase() + formattedLabel.substring(1);

                                final String displayResult = rawPrediction.contains("wheez")
                                        ? "Wheezing Detected"
                                        : "Cough Detected: " + formattedLabel;

                                runOnUiThread(() -> {
                                    hasPredicted = true;
                                    aiCoughLabel = displayResult;
                                    runAiAnalysis();
                                });
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "JSON Parsing error", e);
                    }
                }
            }
        });
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

    private void findNearbyHospitals() {
        Uri gmmIntentUri = Uri.parse("geo:0,0?q=hospitals+near+me");
        Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
        mapIntent.setPackage("com.google.android.apps.maps");
        startActivity(mapIntent);
    }

    @Override
    public void onActuationEvent(long tsMillis) {
        runOnUiThread(() -> {
            totalActuationCount++;
            if (tvLiveActuations != null) {
                tvLiveActuations.setText(totalActuationCount + " Actuations");
            }
            Toast.makeText(LiveSessionActivity.this, "Inhaler Press Detected! Total: " + totalActuationCount, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onConnected(String deviceName) {
        final boolean wasReconnecting = reconnectAttempts > 0;
        reconnectAttempts = 0;
        runOnUiThread(() -> Toast.makeText(this,
                wasReconnecting ? "Reconnected to TANGHAP" : "Connected to TANGHAP",
                Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onDisconnected() {
        if (sessionEndedByUser) return;
        final long droppedAt = System.currentTimeMillis();
        mainHandler.post(() -> {
            if (sessionEndedByUser) return;
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
                if (!sessionEndedByUser && ble != null) ble.connect(deviceAddress);
            }, RECONNECT_DELAY_MS);
        });
    }

    @Override public void onScanResults(List<BleDeviceItem> results) {}

    @Override
    public void onBattery(Integer percent) {
        if (percent != null) {
            SharedPreferences pref = getSharedPreferences("TanghapSessionHistory", Context.MODE_PRIVATE);
            pref.edit().putInt("last_battery", percent).apply();

            runOnUiThread(() -> {
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setSubtitle(" 🔋 " + percent + "%");
                }
            });
        }
    }

    @Override public void onDeviceInfo(String info) {}
    @Override public void onError(String message) {}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sessionEndedByUser = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (ble != null) {
            ble.disconnect();
        }
    }
}