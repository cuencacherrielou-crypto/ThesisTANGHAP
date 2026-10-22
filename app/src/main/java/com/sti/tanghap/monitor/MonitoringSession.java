package com.sti.tanghap.monitor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.sti.tanghap.ai.AiCoughClassifier;
import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.ai.BreathingAnalyzer;
import com.sti.tanghap.ai.CoughClass;
import com.sti.tanghap.ai.CoughReview;
import com.sti.tanghap.ai.PatientProfile;
import com.sti.tanghap.ai.processor.AudioLevel;
import com.sti.tanghap.ai.processor.MfccExtractor;
import com.sti.tanghap.ble.ble.BleDeviceItem;
import com.sti.tanghap.ble.ble.BleListener;
import com.sti.tanghap.ble.ble.BleManager;
import com.sti.tanghap.data.ConnectionLog;
import com.sti.tanghap.data.HealthPrefs;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.data.SessionEvent;
import com.sti.tanghap.data.SessionStore;
import com.sti.tanghap.domain.InhalationTracker;
import com.sti.tanghap.domain.NearbyCoughFilter;
import com.sti.tanghap.notify.NotificationHelper;
import com.sti.tanghap.ui.LiveSessionActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * One monitoring session: connects to the inhaler attachment, records and analyzes the
 * pressure, audio and inhaler-press data, and saves the session when it ends.
 *
 * Owned by {@link MonitoringService}, so it keeps running when the screen is closed or the
 * phone is locked. The live screen only displays it through {@link Listener}.
 * All state is touched on the main thread except the audio/model work, which runs on a
 * single background thread.
 */
public class MonitoringSession implements BleListener {
    private static final String TAG = "MonitoringSession";

    /** app_prefs key for the last inhaler this phone connected to (device setting, not user data). */
    public static final String PREF_LAST_DEVICE = "last_device_address";

    // Pressure analysis
    private static final long BREATH_WINDOW_MS = 30_000;      // BPM is measured over the last 30 s
    private static final long BPM_SAMPLE_INTERVAL_MS = 5_000; // how often a BPM reading enters the session average
    private static final int MAX_RECORDED_SAMPLES = 200_000;  // memory cap for the stored session trace
    private static final int TRACE_POINTS = 200;              // points saved for the result chart
    /** A sequence jump larger than this means the device restarted its counter, not lost packets. */
    private static final long MAX_PLAUSIBLE_GAP = 10_000;

    // Event log limits (only frequent breathing-rate readings and inhalations are capped)
    private static final int MAX_EVENTS = 3000;
    private static final int MAX_INHALATION_EVENTS = 1500;

    // Cough model audio windows (16 kHz 16-bit mono)
    private static final int CLIP_BYTES = MfccExtractor.CLIP_SAMPLES * 2; // 2 s, the clip length the model was trained on
    private static final int CLIP_HOP_BYTES = CLIP_BYTES / 2;             // 1 s step, so a cough on a window edge is still heard whole
    private static final float AUDIO_BYTES_PER_MS = 16_000 * 2 / 1000f;  // 16 kHz x 2 bytes
    private static final long CLIP_HALF_MS = 1000;                        // a clip's event time is its middle

    // Auto-reconnect after an unexpected Bluetooth drop
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 3000;
    private static final long SLOW_RECONNECT_DELAY_MS = 15_000;

    // Cough calibration
    private static final long CALIBRATION_TIMEOUT_MS = 30_000;

    // Live chart history kept so a reopened screen can redraw it
    private static final int LIVE_PRESSURE_POINTS = 150;
    private static final int LIVE_COUGH_BARS = 30;

    /** What the live screen shows. */
    public static final class Snapshot {
        public long startMillis;
        public boolean connected;
        public int bpm;
        public String pattern;
        public float currentEffortKpa;
        public int actuations;
        public int countedCoughs;
        public String aiLabel;
        public boolean hasPrediction;
        public String possibleCondition;
        public String observations;
        public String medicationGuidance;
        public boolean worsening;
        public Integer batteryPercent;
    }

    /** The live screen, when it is open. All calls arrive on the main thread. */
    public interface Listener {
        void onUpdate(Snapshot snapshot);

        void onPressureSamples(float[] kpaSamples);

        void onCoughBar(int confidencePercent);

        void onMessage(String text);

        /** Asks the user whether to calibrate their cough loudness. */
        void onCalibrationPrompt(boolean hasPreviousCalibration);

        /**
         * The session ended. sessionId is the saved session, or null when nothing was saved
         * (message says why).
         */
        void onFinished(String sessionId, String message);
    }

    /** Lets the service update its notification and stop itself. */
    interface Host {
        void onSessionChanged(MonitoringSession session);

        void onSessionFinished(MonitoringSession session, String sessionId, String message);
    }

    private final Context ctx;
    private final Host host;
    private final String deviceAddress;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;

    private BleManager ble;
    private final BreathingAnalyzer breathingAnalyzer = new BreathingAnalyzer();
    private final AssessmentEngine assessmentEngine = new AssessmentEngine();
    private final InhalationTracker inhalationTracker = new InhalationTracker(BreathingAnalyzer.INHALATION_THRESHOLD_KPA);
    private final PatientProfile profile;
    private boolean worseningNotified = false;
    private int reconnectAttempts = 0;

    // ---- Main-thread state ----
    private final long sessionStartMillis = System.currentTimeMillis();
    private final String userId;
    private final ArrayDeque<PressurePacket> pressureWindow = new ArrayDeque<>();
    private float[] recordedSamples = new float[4096];
    /** Estimated time of each recorded sample, in ms from sessionStartMillis. */
    private int[] recordedTimes = new int[4096];
    private int recordedCount = 0;
    private float maxInhaleEffort = 0.0f;
    private float currentEffort = 0.0f;
    private int bpmSum = 0;
    private int bpmReadings = 0;
    private long lastBpmReadingMillis = 0;
    private BreathingAnalyzer.BreathingResult lastBreathing = BreathingAnalyzer.classify(0);
    private AssessmentEngine.AssessmentResult lastAssessment;
    private long lastSequence = -1;
    private int droppedPackets = 0;
    private boolean connected = false;
    private boolean everConnected = false;
    private int clipsClassified = 0;
    private Integer batteryPercent = null;
    /** Time-stamped readings and events for this session. */
    private final List<SessionEvent> events = new ArrayList<>();
    private final ArrayDeque<Float> livePressure = new ArrayDeque<>();
    private final ArrayDeque<Integer> liveCoughBars = new ArrayDeque<>();

    private int countedCoughs = 0;
    private int totalActuationCount = 0;
    private String aiCoughLabel = "Analyzing respiration pattern...";
    /** Latest counted cough or wheeze the model heard; null until it hears one. */
    private CoughClass predictedCough = null;
    private volatile boolean sessionEnding = false;
    private boolean finished = false;

    // ---- Audio-thread state (only touched inside audioExecutor) ----
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private AiCoughClassifier coughClassifier;
    private final boolean modelLoaded;
    private final ByteArrayOutputStream audioWindow = new ByteArrayOutputStream();
    private long lastAudioArrivalMillis = 0;

    // ---- Nearby-cough filter (main thread) ----
    private final Float savedCoughReferenceDb;
    private final NearbyCoughFilter coughFilter;
    /** The cough currently being heard across overlapping clips; null between coughs. */
    private CoughEpisode coughEpisode = null;
    private boolean lastClipWasWheeze = false;
    private boolean calibrationPromptPending = false;
    private boolean calibrationOffered = false;

    private static class PressurePacket {
        final long arrivalMillis;
        final float[] samples;

        PressurePacket(long arrivalMillis, float[] samples) {
            this.arrivalMillis = arrivalMillis;
            this.samples = samples;
        }
    }

    MonitoringSession(Context context, Host host, String deviceAddress) {
        this.ctx = context.getApplicationContext();
        this.host = host;
        this.deviceAddress = deviceAddress;
        this.userId = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        // Health profile answers used by the assessment
        profile = HealthPrefs.loadProfile(ctx);
        // Until the user chooses, the last calibration (if any) filters nearby coughs
        savedCoughReferenceDb = HealthPrefs.coughReferenceDb(ctx);
        coughFilter = new NearbyCoughFilter(savedCoughReferenceDb);

        AiCoughClassifier classifier = null;
        try {
            classifier = new AiCoughClassifier(ctx);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to load cough model", e);
            aiCoughLabel = "Cough model unavailable";
        }
        coughClassifier = classifier;
        modelLoaded = classifier != null;
        lastAssessment = assessmentEngine.generatePreliminaryAssessment(lastBreathing, null, profile);
    }

    void start() {
        ble = new BleManager(ctx, this);
        if (!ble.connect(deviceAddress)) scheduleReconnect();
    }

    // =========================================================
    // LIVE SCREEN
    // =========================================================

    public void setListener(Listener l) {
        listener = l;
        if (l == null) return;
        l.onUpdate(snapshot());
        if (calibrationPromptPending && !sessionEnding) {
            calibrationPromptPending = false;
            l.onCalibrationPrompt(savedCoughReferenceDb != null);
        }
    }

    /** Pressure points currently on the live chart, oldest first (for redrawing a reopened screen). */
    public float[] recentPressure() {
        float[] out = new float[livePressure.size()];
        int i = 0;
        for (Float f : livePressure) out[i++] = f;
        return out;
    }

    public int[] recentCoughBars() {
        int[] out = new int[liveCoughBars.size()];
        int i = 0;
        for (Integer v : liveCoughBars) out[i++] = v;
        return out;
    }

    public boolean isEnding() {
        return sessionEnding;
    }

    public long getStartMillis() {
        return sessionStartMillis;
    }

    public int getCountedCoughs() {
        return countedCoughs;
    }

    public int getActuations() {
        return totalActuationCount;
    }

    public boolean isConnected() {
        return connected;
    }

    public Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.startMillis = sessionStartMillis;
        s.connected = connected;
        s.bpm = lastBreathing.bpm;
        s.pattern = lastBreathing.pattern;
        s.currentEffortKpa = currentEffort;
        s.actuations = totalActuationCount;
        s.countedCoughs = countedCoughs;
        s.aiLabel = aiCoughLabel;
        s.hasPrediction = predictedCough != null;
        s.batteryPercent = batteryPercent;
        if (lastAssessment != null) {
            s.possibleCondition = lastAssessment.possibleCondition;
            s.observations = String.join(" • ", lastAssessment.observations);
            s.medicationGuidance = lastAssessment.medicationGuidance;
            s.worsening = s.hasPrediction && lastAssessment.recommendHospital;
        }
        return s;
    }

    private void publish() {
        if (listener != null) listener.onUpdate(snapshot());
        host.onSessionChanged(this);
    }

    private void message(String text) {
        if (listener != null) listener.onMessage(text);
    }

    // =========================================================
    // END OF SESSION
    // =========================================================

    /** Stops recording and saves the session. */
    public void endAndSave() {
        if (sessionEnding) return;
        sessionEnding = true;
        if (ble != null) ble.disconnect();
        // Let the audio thread post any clip it is still classifying, then finish on the main thread
        runOnAudioThread(() -> main.post(this::finishAndSave));
    }

    /** Stops recording without saving. */
    public void discard() {
        if (sessionEnding) return;
        sessionEnding = true;
        if (ble != null) ble.disconnect();
        finish(null, "Session discarded. Nothing was saved.");
    }

    private void finishAndSave() {
        long endMillis = System.currentTimeMillis();

        // Nothing came from the device: an empty session would only distort history and baselines
        if (recordedCount == 0 && clipsClassified == 0 && totalActuationCount == 0) {
            finish(null, everConnected
                    ? "No sensor data was received from the device, so this session was not saved."
                    : "The device never connected, so this session was not saved.");
            return;
        }
        if (userId == null) {
            finish(null, "Not signed in: this session was not saved.");
            return;
        }

        // A cough still in progress and an unfinished calibration are settled first
        if (coughEpisode != null) finishCoughEpisode();
        if (coughFilter.isCalibrating()) finishCalibration();

        int avgBpm = bpmReadings > 0 ? Math.round(bpmSum / (float) bpmReadings) : lastBreathing.bpm;
        BreathingAnalyzer.BreathingResult sessionBreathing = BreathingAnalyzer.classify(avgBpm, profile.ageYears);

        inhalationTracker.finish();

        // Each inhalation, with the time it started, goes into the event log
        List<InhalationTracker.Inhalation> inhalations = inhalationTracker.getInhalations();
        int from = Math.max(0, inhalations.size() - MAX_INHALATION_EVENTS);
        for (int i = from; i < inhalations.size(); i++) {
            InhalationTracker.Inhalation in = inhalations.get(i);
            events.add(new SessionEvent(in.startMillis, SessionEvent.Type.INHALATION, in.durationMillis,
                    String.format(Locale.US, "%.1f", in.peakKpa)));
        }
        events.sort((a, b) -> Long.compare(a.timeMillis, b.timeMillis));

        // Cough count, finding and assessment come from the counted coughs in the event log,
        // the same way the result screen recalculates them after a cough review
        CoughReview.Outcome review = CoughReview.evaluate(events, avgBpm, profile);
        AssessmentEngine.AssessmentResult assessment = review.assessment;
        String[] trace = buildPressureTrace();

        SessionEntity s = new SessionEntity(UUID.randomUUID().toString());
        s.userId = userId;
        s.startTimeMillis = sessionStartMillis;
        s.endTimeMillis = endMillis;
        s.peakPressureKpa = maxInhaleEffort;
        s.inhaleDurationMillis = inhalationTracker.getAverageInhaleMillis();
        s.averageBpm = avgBpm;
        s.detectedPattern = sessionBreathing.pattern;
        s.coughCount = review.coughCount;
        s.actuationCount = totalActuationCount;
        s.coughFinding = review.findingText();
        s.aiPreliminaryInsight = assessment.insight;
        s.aiObservations = String.join(" • ", assessment.observations);
        s.possibleCondition = assessment.possibleCondition;
        s.medicineKey = assessment.medicineKey;
        s.otcRecommendation = assessment.medicationGuidance;
        s.riskLevelHigh = assessment.recommendHospital;
        s.recommendation = assessment.guidance;
        s.pressureTrace = trace[0];
        s.pressureTraceTimes = trace[1];
        s.eventLog = SessionEvent.encode(events);
        s.droppedPackets = droppedPackets;

        ctx.getSharedPreferences(HealthPrefs.SESSION_PREFS, Context.MODE_PRIVATE).edit()
                .putLong("last_timestamp", endMillis)
                .apply();

        // Saved by the service itself, so it is kept even if the screen is already closed
        new Thread(() -> {
            String id = null;
            String msg = null;
            try {
                SessionStore.saveNew(ctx, s);
                id = s.id;
            } catch (RuntimeException e) {
                Log.e(TAG, "Saving the session failed", e);
                msg = "The session could not be saved: " + e.getMessage();
            }
            final String savedId = id;
            final String error = msg;
            main.post(() -> finish(savedId, error));
        }, "save-session").start();
    }

    private void finish(String sessionId, String msg) {
        if (finished) return;
        finished = true;
        main.removeCallbacksAndMessages(null);
        runOnAudioThread(() -> {
            if (coughClassifier != null) coughClassifier.close();
            coughClassifier = null;
        });
        audioExecutor.shutdown();
        if (listener != null) listener.onFinished(sessionId, msg);
        host.onSessionFinished(this, sessionId, msg);
    }

    /**
     * Down-sampled recorded pressure, keeping the largest-magnitude value of each bucket so
     * peaks survive. Returns {values, times}: comma-separated kPa values and, for each value,
     * its time in ms from the session start.
     */
    private String[] buildPressureTrace() {
        if (recordedCount == 0) return new String[]{null, null};
        int points = Math.min(TRACE_POINTS, recordedCount);
        StringBuilder values = new StringBuilder();
        StringBuilder times = new StringBuilder();
        for (int p = 0; p < points; p++) {
            int from = (int) ((long) p * recordedCount / points);
            int to = (int) ((long) (p + 1) * recordedCount / points);
            int best = from;
            for (int i = from + 1; i < to; i++) {
                if (Math.abs(recordedSamples[i]) > Math.abs(recordedSamples[best])) best = i;
            }
            if (p > 0) {
                values.append(',');
                times.append(',');
            }
            values.append(String.format(Locale.US, "%.2f", recordedSamples[best]));
            times.append(recordedTimes[best]);
        }
        return new String[]{values.toString(), times.toString()};
    }

    /**
     * Coughs, inhaler presses and connection events are always kept so daily totals are complete;
     * only the frequent breathing-rate readings are capped (the session average still uses all of them).
     */
    private void addEvent(SessionEvent e) {
        if (e.type != SessionEvent.Type.BREATHING_RATE || events.size() < MAX_EVENTS) events.add(e);
    }

    // =========================================================
    // PRESSURE (main thread)
    // =========================================================

    @Override
    public void onPressureSamples(long sequence, float[] kpaSamples) {
        final long arrival = System.currentTimeMillis();
        main.post(() -> handlePressurePacket(arrival, sequence, kpaSamples));
    }

    private void handlePressurePacket(long arrival, long sequence, float[] kpaSamples) {
        if (sessionEnding || kpaSamples.length == 0) return;

        trackSequence(arrival, sequence);
        inhalationTracker.addSamples(kpaSamples, arrival);

        pressureWindow.addLast(new PressurePacket(arrival, kpaSamples));
        while (pressureWindow.size() > 1 && arrival - pressureWindow.peekFirst().arrivalMillis > BREATH_WINDOW_MS) {
            pressureWindow.removeFirst();
        }

        // The packet's last sample arrives with it; earlier samples are one sample period apart
        float msPerSample = inhalationTracker.msPerSample();
        float currentPeak = 0f;
        int n = kpaSamples.length;
        for (int i = 0; i < n; i++) {
            float val = kpaSamples[i];
            // Effort is the magnitude, so it works whether inhalation reads as + or - kPa
            float effort = Math.abs(val);
            if (effort > currentPeak) currentPeak = effort;

            long sampleTime = arrival - Math.round((n - 1 - i) * msPerSample);
            recordSample(val, sampleTime);

            livePressure.addLast(val);
            if (livePressure.size() > LIVE_PRESSURE_POINTS) livePressure.removeFirst();
        }
        currentEffort = currentPeak;
        if (currentPeak > maxInhaleEffort) maxInhaleEffort = currentPeak;

        if (listener != null) listener.onPressureSamples(kpaSamples);
        runAiAnalysis();
    }

    /** Counts packets lost in transmission from gaps in the device's packet counter. */
    private void trackSequence(long arrival, long sequence) {
        if (lastSequence >= 0 && sequence > lastSequence + 1) {
            long missing = sequence - lastSequence - 1;
            if (missing <= MAX_PLAUSIBLE_GAP) {
                droppedPackets += (int) missing;
                addEvent(new SessionEvent(arrival, SessionEvent.Type.DATA_GAP, missing, ""));
            }
        }
        // A lower or equal number means the device restarted its counter: start counting again
        lastSequence = sequence;
    }

    private void recordSample(float val, long sampleTimeMillis) {
        if (recordedCount >= MAX_RECORDED_SAMPLES) return;
        if (recordedCount == recordedSamples.length) {
            int newSize = Math.min(recordedSamples.length * 2, MAX_RECORDED_SAMPLES);
            recordedSamples = Arrays.copyOf(recordedSamples, newSize);
            recordedTimes = Arrays.copyOf(recordedTimes, newSize);
        }
        recordedSamples[recordedCount] = val;
        recordedTimes[recordedCount] = (int) Math.max(0, sampleTimeMillis - sessionStartMillis);
        recordedCount++;
    }

    // =========================================================
    // ASSESSMENT (main thread)
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

        BreathingAnalyzer.BreathingResult br = breathingAnalyzer.analyze(dataArray, windowSec, profile.ageYears);
        lastBreathing = br;

        long now = System.currentTimeMillis();
        if (br.bpm > 0 && now - lastBpmReadingMillis >= BPM_SAMPLE_INTERVAL_MS) {
            lastBpmReadingMillis = now;
            bpmSum += br.bpm;
            bpmReadings++;
            addEvent(new SessionEvent(now, SessionEvent.Type.BREATHING_RATE, br.bpm, ""));
        }

        lastAssessment = assessmentEngine.generatePreliminaryAssessment(br, predictedCough, profile);

        boolean worsening = predictedCough != null && lastAssessment.recommendHospital;
        if (worsening && !worseningNotified) {
            worseningNotified = true;
            NotificationHelper.show(ctx, NotificationHelper.ID_WORSENING,
                    "Worsening respiratory pattern",
                    lastAssessment.possibleCondition + ". Open TANGHAP for guidance, nearby hospitals and "
                            + "emergency calling. This is not a diagnosis.",
                    LiveSessionActivity.openFromNotification(ctx));
        }
        publish();
    }

    /**
     * Handles one classified 2-second clip. Overlapping clips hear the same cough more than
     * once, so consecutive cough clips form one cough episode; the episode is decided when
     * it ends (the next clip without a cough), using its loudest clip and the model's most
     * confident cough type.
     */
    private void onClipClassified(AiCoughClassifier.Result result, long clipTimeMillis, float levelDb) {
        if (sessionEnding) return;
        clipsClassified++;
        boolean confident = result.isConfident();
        boolean coughClip = confident && result.type.isCough;
        int bar = coughClip ? result.percent() : 0;
        liveCoughBars.addLast(bar);
        if (liveCoughBars.size() > LIVE_COUGH_BARS) liveCoughBars.removeFirst();
        if (listener != null) listener.onCoughBar(bar);

        if (coughClip) {
            if (coughEpisode == null) coughEpisode = new CoughEpisode(clipTimeMillis);
            coughEpisode.add(result.type, result.percent(), levelDb);
        } else if (coughEpisode != null) {
            finishCoughEpisode();
        }

        // Wheezing is not loudness-filtered: it is much quieter than a cough
        boolean wheezeClip = confident && result.type == CoughClass.WHEEZING;
        if (wheezeClip && !lastClipWasWheeze) {
            addEvent(new SessionEvent(clipTimeMillis, SessionEvent.Type.WHEEZE, result.percent(), result.type.label));
        }
        lastClipWasWheeze = wheezeClip;
        if (wheezeClip) {
            predictedCough = CoughClass.WHEEZING;
            aiCoughLabel = CoughClass.WHEEZING.displayText(result.percent());
        }
        runAiAnalysis();
    }

    /** Decides a finished cough episode: calibration cough, the user's cough, or someone nearby. */
    private void finishCoughEpisode() {
        CoughEpisode e = coughEpisode;
        coughEpisode = null;

        if (coughFilter.isCalibrating()) {
            // Calibration coughs are made on purpose: they are not symptoms and are not counted
            if (coughFilter.addCalibrationCough(e.maxLevelDb)) {
                finishCalibration();
            } else {
                aiCoughLabel = "Calibrating: " + coughFilter.calibrationCoughsHeard() + " of "
                        + NearbyCoughFilter.CALIBRATION_COUGHS + " coughs heard. Please cough again.";
            }
            return;
        }

        if (coughFilter.isFromUser(e.maxLevelDb)) {
            addEvent(new SessionEvent(e.startMillis, SessionEvent.Type.COUGH, e.bestPercent, e.bestType.label));
            countedCoughs++;
            predictedCough = e.bestType;
            aiCoughLabel = e.bestType.displayText(e.bestPercent);
        } else {
            addEvent(new SessionEvent(e.startMillis, SessionEvent.Type.COUGH_IGNORED, e.bestPercent, e.bestType.label));
            aiCoughLabel = "Quieter cough not counted (likely someone nearby)";
        }
    }

    /** One cough heard across one or more consecutive overlapping clips. */
    private static final class CoughEpisode {
        final long startMillis;
        CoughClass bestType;
        int bestPercent = -1;
        float maxLevelDb = AudioLevel.SILENCE_DBFS;

        CoughEpisode(long startMillis) {
            this.startMillis = startMillis;
        }

        void add(CoughClass type, int percent, float levelDb) {
            if (percent > bestPercent) {
                bestPercent = percent;
                bestType = type;
            }
            maxLevelDb = Math.max(maxLevelDb, levelDb);
        }
    }

    // =========================================================
    // COUGH CALIBRATION (main thread)
    // =========================================================

    /** The user chose to calibrate now. */
    public void startCalibration() {
        if (sessionEnding) return;
        // A cough already in progress happened before the user was asked to cough
        if (coughEpisode != null) finishCoughEpisode();
        coughFilter.startCalibration();
        aiCoughLabel = "Calibrating: please cough " + NearbyCoughFilter.CALIBRATION_COUGHS + " times now.";
        main.postDelayed(calibrationTimeout, CALIBRATION_TIMEOUT_MS);
        publish();
    }

    /** The user chose to keep the last calibration (already in use). */
    public void useLastCalibration() {
        message("Using your last cough calibration.");
    }

    /** The user skipped calibration: every cough is counted. */
    public void skipCalibration() {
        coughFilter.disable();
        message("Nearby-cough filter is off: every cough will be counted. "
                + "You can remove coughs that are not yours on the result screen.");
    }

    private final Runnable calibrationTimeout = this::onCalibrationTimeout;

    private void onCalibrationTimeout() {
        if (coughFilter.isCalibrating()) {
            finishCalibration();
            publish();
        }
    }

    private void finishCalibration() {
        main.removeCallbacks(calibrationTimeout);
        int heard = coughFilter.calibrationCoughsHeard();
        if (coughFilter.finishCalibration()) {
            float level = coughFilter.getReferenceDb();
            HealthPrefs.saveCoughReferenceDb(ctx, level);
            addEvent(new SessionEvent(System.currentTimeMillis(), SessionEvent.Type.CALIBRATION,
                    level, String.valueOf(heard)));
            aiCoughLabel = "Calibration done. Monitoring your coughs...";
            message("Cough calibration done.");
        } else {
            aiCoughLabel = "Analyzing respiration pattern...";
            message(coughFilter.getReferenceDb() != null
                    ? "No cough heard. Using your last cough calibration."
                    : "No cough heard. Nearby-cough filter is off; review coughs on the result screen.");
        }
    }

    // =========================================================
    // AUDIO + COUGH MODEL (audio thread)
    // =========================================================

    @Override
    public void onAudioDataReceived(byte[] audioBytes) {
        final byte[] copy = audioBytes.clone();
        final long arrival = System.currentTimeMillis();
        runOnAudioThread(() -> processAudio(copy, arrival));
    }

    private void runOnAudioThread(Runnable r) {
        try {
            audioExecutor.execute(r);
        } catch (RejectedExecutionException ignored) {
            // Session finished; drop late BLE data
        }
    }

    /** Classifies every 2-second window of the stream, stepping 1 second at a time. */
    private void processAudio(byte[] audioBytes, long arrivalMillis) {
        if (coughClassifier == null) return;
        audioWindow.write(audioBytes, 0, audioBytes.length);
        lastAudioArrivalMillis = arrivalMillis;

        while (audioWindow.size() >= CLIP_BYTES) {
            byte[] buffered = audioWindow.toByteArray();
            byte[] clip = Arrays.copyOf(buffered, CLIP_BYTES);
            audioWindow.reset();
            audioWindow.write(buffered, CLIP_HOP_BYTES, buffered.length - CLIP_HOP_BYTES);

            // The newest buffered byte arrived just now; this clip ended (buffered - clip) bytes earlier
            long clipEndMillis = lastAudioArrivalMillis
                    - Math.round((buffered.length - CLIP_BYTES) / AUDIO_BYTES_PER_MS);
            long clipTimeMillis = clipEndMillis - CLIP_HALF_MS;

            AiCoughClassifier.Result result;
            try {
                // The raw clip goes to the model unchanged, exactly as in training (librosa.load)
                result = coughClassifier.classify(clip);
            } catch (RuntimeException e) {
                Log.e(TAG, "Cough model failed on an audio clip", e);
                continue;
            }

            // Loudness is only measured, for the nearby-cough filter; the model got the clip unchanged
            float levelDb = AudioLevel.peakDbfs(clip);
            main.post(() -> onClipClassified(result, clipTimeMillis, levelDb));
        }
    }

    // =========================================================
    // OTHER BLE EVENTS
    // =========================================================

    @Override
    public void onActuationEvent(long tsMillis) {
        main.post(() -> {
            if (sessionEnding) return;
            totalActuationCount++;
            addEvent(new SessionEvent(tsMillis, SessionEvent.Type.ACTUATION, 1, ""));
            message("Inhaler press detected. Total: " + totalActuationCount);
            publish();
        });
    }

    @Override
    public void onConnected(String deviceName) {
        final long connectedAt = System.currentTimeMillis();
        ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
                .putString(PREF_LAST_DEVICE, deviceAddress)
                .apply();
        main.post(() -> {
            if (sessionEnding) return;
            boolean wasReconnecting = everConnected;
            everConnected = true;
            connected = true;
            reconnectAttempts = 0;
            // A reconnected device may restart its packet counter
            lastSequence = -1;
            addEvent(new SessionEvent(connectedAt, SessionEvent.Type.CONNECTED, wasReconnecting ? 1 : 0, deviceName));
            message(wasReconnecting ? "Reconnected to TANGHAP" : "Connected to TANGHAP");
            if (!calibrationOffered && modelLoaded) {
                calibrationOffered = true;
                if (listener != null) listener.onCalibrationPrompt(savedCoughReferenceDb != null);
                else calibrationPromptPending = true; // asked when the screen is opened
            }
            publish();
        });
    }

    @Override
    public void onDisconnected() {
        if (sessionEnding) return;
        final long droppedAt = System.currentTimeMillis();
        main.post(() -> {
            if (sessionEnding) return;
            connected = false;
            if (reconnectAttempts == 0) {
                // First drop only; failed reconnect attempts also land here and should not repeat the alert.
                ConnectionLog.recordDisconnect(ctx, droppedAt);
                if (everConnected) addEvent(new SessionEvent(droppedAt, SessionEvent.Type.DISCONNECTED, 0, ""));
                NotificationHelper.show(ctx, NotificationHelper.ID_CONNECTION,
                        "TANGHAP device disconnected",
                        "The inhaler attachment lost its Bluetooth connection. Keep your phone within range; the app will try to reconnect.",
                        LiveSessionActivity.openFromNotification(ctx));
            }
            publish();
            scheduleReconnect();
        });
    }

    /**
     * Quick retries first, then keeps trying slowly for as long as the session runs, so
     * monitoring resumes by itself when the device comes back in range or Bluetooth is turned on.
     */
    private void scheduleReconnect() {
        if (sessionEnding) return;
        reconnectAttempts++;
        long delay;
        if (reconnectAttempts <= MAX_RECONNECT_ATTEMPTS) {
            delay = RECONNECT_DELAY_MS;
            message((everConnected ? "Device disconnected. Reconnecting" : "Could not reach the device. Retrying")
                    + " (attempt " + reconnectAttempts + " of " + MAX_RECONNECT_ATTEMPTS + ")...");
        } else {
            delay = SLOW_RECONNECT_DELAY_MS;
            if (reconnectAttempts == MAX_RECONNECT_ATTEMPTS + 1) {
                message("Still not connected. Bring the device closer and check Bluetooth; TANGHAP keeps trying every "
                        + SLOW_RECONNECT_DELAY_MS / 1000 + " seconds.");
            }
        }
        main.postDelayed(() -> {
            if (sessionEnding || ble == null) return;
            // No attempt started (e.g. Bluetooth off): no callback will come, so retry again
            if (!ble.connect(deviceAddress)) scheduleReconnect();
        }, delay);
    }

    @Override public void onScanResults(List<BleDeviceItem> results) {}

    @Override
    public void onBattery(Integer percent) {
        if (percent == null) return;
        ctx.getSharedPreferences(HealthPrefs.SESSION_PREFS, Context.MODE_PRIVATE).edit()
                .putInt("last_battery", percent).apply();
        main.post(() -> {
            batteryPercent = percent;
            publish();
        });
    }

    @Override public void onDeviceInfo(String info) {}

    @Override
    public void onError(String msg) {
        Log.e(TAG, "BLE error: " + msg);
        main.post(() -> message(msg));
    }
}
