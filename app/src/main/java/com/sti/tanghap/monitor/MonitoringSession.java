package com.sti.tanghap.monitor;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.sti.tanghap.ai.AiCoughClassifier;
import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.ai.BreathSoundAnalyzer;
import com.sti.tanghap.ai.BreathingAnalyzer;
import com.sti.tanghap.ai.CoughClass;
import com.sti.tanghap.ai.CoughEvidence;
import com.sti.tanghap.ai.CoughReview;
import com.sti.tanghap.ai.PatientProfile;
import com.sti.tanghap.ai.processor.CoughDetector;
import com.sti.tanghap.ai.processor.MfccExtractor;
import com.sti.tanghap.ai.processor.PcmRingBuffer;
import com.sti.tanghap.ble.ble.BleDeviceItem;
import com.sti.tanghap.ble.ble.BleListener;
import com.sti.tanghap.ble.ble.BleManager;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.ConnectionLog;
import com.sti.tanghap.data.HealthPrefs;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.data.SessionEvent;
import com.sti.tanghap.data.SessionStore;
import com.sti.tanghap.domain.BatteryAlert;
import com.sti.tanghap.domain.CoughHistory;
import com.sti.tanghap.domain.InhalationTracker;
import com.sti.tanghap.notify.NotificationHelper;
import com.sti.tanghap.ui.LiveSessionActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    // Breathing pattern from the breathing sounds (the pressure sensor is not used for it for now)
    private static final int LIVE_BREATH_WINDOW_S = 60;   // the live pattern is judged on the last 60 s
    private static final int LIVE_BREATH_EVERY_S = 5;     // and updated every 5 s of audio

    // Pressure recording
    private static final int MAX_RECORDED_SAMPLES = 200_000;  // memory cap for the stored session trace
    private static final int TRACE_POINTS = 200;              // points saved for the result chart
    /** A sequence jump larger than this means the device restarted its counter, not lost packets. */
    private static final long MAX_PLAUSIBLE_GAP = 10_000;

    // Event log limits (only frequent inhalation events are capped)
    private static final int MAX_EVENTS = 3000;
    private static final int MAX_INHALATION_EVENTS = 1500;

    // Cough model audio windows (16 kHz 16-bit mono)
    private static final int CLIP_BYTES = MfccExtractor.CLIP_SAMPLES * 2; // 2 s, the clip length the model was trained on
    private static final int CLIP_HOP_BYTES = CLIP_BYTES / 2;             // 1 s step, so a cough on a window edge is still heard whole
    private static final float AUDIO_BYTES_PER_MS = 16_000 * 2 / 1000f;  // 16 kHz x 2 bytes
    private static final long CLIP_HALF_MS = 1000;                        // a clip's event time is its middle
    private static final float AUDIO_SAMPLES_PER_MS = AUDIO_BYTES_PER_MS / 2;
    private static final int RECENT_AUDIO_SAMPLES = 6 * MfccExtractor.SAMPLE_RATE; // room for a 3 s cough plus the clip around it
    private static final int LEVEL_FRAMES_PER_BAR = 5;                    // one sound-level bar per 250 ms
    private static final float LEVEL_BAR_MIN_DB = -60f;                   // sound level shown as 0% .. 100% for -60 .. 0 dBFS
    /** Audio kept after a sensed sound in its clip (250 ms), so the model hears how it ends. */
    static final int CLIP_TAIL_SAMPLES = MfccExtractor.SAMPLE_RATE / 4;
    /**
     * Debug builds only: each session's microphone audio, one file per session named by its start
     * time, for checking the device's sound and testing the cough model on real device audio.
     */
    private static final String DEBUG_AUDIO_DIR = "debug_audio";
    private static final long DEBUG_AUDIO_MAX_BYTES = 5L * 60 * MfccExtractor.SAMPLE_RATE * 2; // 5 min
    private static final int DEBUG_AUDIO_KEEP = 20;                                          // newest sessions kept
    /** Debug builds only: how often the raw pressure range is logged, for tuning the inhalation threshold. */
    private static final long PRESSURE_DEBUG_LOG_MS = 1000;
    /** Wheezing heard again within this time is the same wheeze, not a new one. */
    private static final long WHEEZE_REPEAT_MS = 2000;
    /**
     * A wheeze is logged only when heard again 2-6 s after an earlier confident wheeze: a real
     * wheeze lasts and returns with the next breaths, a single airflow noise does not. Under 2 s
     * apart the two overlapping 2-second windows may hold the same sound, so that does not confirm.
     */
    private static final long WHEEZE_CONFIRM_MIN_MS = 2000;
    private static final long WHEEZE_CONFIRM_MAX_MS = 6000;

    // Auto-reconnect after an unexpected Bluetooth drop
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 3000;
    private static final long SLOW_RECONNECT_DELAY_MS = 15_000;
    // While the saved device has not answered, scan this long for any TANGHAP board (e.g. a replaced one)
    private static final long DEVICE_SEARCH_MS = 8000;
    private boolean searchingForDevice = false;
    private final Runnable endDeviceSearch = this::endDeviceSearch;

    // Live chart history kept so a reopened screen can redraw it
    private static final int LIVE_SOUND_BARS = 30;

    /** What the live screen shows. */
    public static final class Snapshot {
        public long startMillis;
        public boolean connected;
        /** Breathing pattern from the breathing sounds, and what it means or what to do. */
        public String pattern;
        public String breathingInsight;
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

        /** Microphone loudness over the last 250 ms, 0-100. */
        void onSoundLevel(int levelPercent);

        void onMessage(String text);

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
    // Can change during the session when the saved device is gone and another TANGHAP board answers
    private volatile String deviceAddress;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;

    private BleManager ble;
    private final AssessmentEngine assessmentEngine = new AssessmentEngine();
    private final InhalationTracker inhalationTracker = new InhalationTracker(BreathingAnalyzer.INHALATION_THRESHOLD_KPA);
    private final PatientProfile profile;
    private boolean worseningNotified = false;
    private int reconnectAttempts = 0;
    private long pressureDebugSinceMillis = 0;
    private float pressureDebugMin = Float.MAX_VALUE;
    private float pressureDebugMax = -Float.MAX_VALUE;
    private double pressureDebugSum = 0;
    private int pressureDebugCount = 0;

    // ---- Main-thread state ----
    private final long sessionStartMillis = System.currentTimeMillis();
    private final String userId;
    private float[] recordedSamples = new float[4096];
    /** Estimated time of each recorded sample, in ms from sessionStartMillis. */
    private int[] recordedTimes = new int[4096];
    private int recordedCount = 0;
    private BreathingAnalyzer.BreathingResult lastBreathing;
    private AssessmentEngine.AssessmentResult lastAssessment;
    private long lastSequence = -1;
    private int droppedPackets = 0;
    private boolean connected = false;
    private boolean everConnected = false;
    private int clipsClassified = 0;
    private Integer batteryPercent = null;
    /** Time-stamped readings and events for this session. */
    private final List<SessionEvent> events = new ArrayList<>();
    private final ArrayDeque<Integer> liveSoundLevels = new ArrayDeque<>();

    private int countedCoughs = 0;
    private int totalActuationCount = 0;
    private String aiCoughLabel = "Analyzing respiration pattern...";
    /** Latest counted cough or wheeze the model heard; null until it hears one. */
    private CoughClass predictedCough = null;
    /** Counted coughs and logged wheezes of the user's earlier sessions, loaded in the background at the start. */
    private volatile List<Long> priorCoughTimes = Collections.emptyList();
    private volatile List<Long> priorWheezeTimes = Collections.emptyList();
    private volatile boolean sessionEnding = false;
    private boolean finished = false;

    // ---- Audio-thread state (only touched inside audioExecutor) ----
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private AiCoughClassifier coughClassifier;
    private final ByteArrayOutputStream audioWindow = new ByteArrayOutputStream();
    private long lastAudioArrivalMillis = 0;
    /** Finds cough-like sounds in the microphone stream; the model then decides what each one is. */
    private final CoughDetector coughDetector = new CoughDetector();
    private final PcmRingBuffer recentAudio = new PcmRingBuffer(RECENT_AUDIO_SAMPLES);
    /** Breathing pattern from the same audio stream; told about each counted cough so it skips it. */
    private final BreathSoundAnalyzer breathSound = new BreathSoundAnalyzer();
    private long breathSamplesSinceReading = 0;
    /** Sensed sounds waiting until the audio after them has arrived, so their clip is complete. */
    private final ArrayDeque<CoughDetector.Cough> pendingSounds = new ArrayDeque<>();
    private float levelBarMaxDb = CoughDetector.SILENCE_DB;
    private FileOutputStream debugAudio;
    private long debugAudioBytes = 0;
    private int levelBarFrames = 0;

    // ---- Coughs and wheezing (main thread) ----
    private long lastWheezeEventMillis = Long.MIN_VALUE / 2;
    /** Times of recent confident wheeze detections still waiting for a confirming one. */
    private final ArrayDeque<Long> wheezeCandidates = new ArrayDeque<>();

    MonitoringSession(Context context, Host host, String deviceAddress) {
        this.ctx = context.getApplicationContext();
        this.host = host;
        this.deviceAddress = deviceAddress;
        this.userId = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        // Health profile answers used by the assessment
        profile = HealthPrefs.loadProfile(ctx);
        loadCoughHistory();

        AiCoughClassifier classifier = null;
        try {
            classifier = new AiCoughClassifier(ctx);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to load cough model", e);
            aiCoughLabel = "Cough model unavailable";
        }
        coughClassifier = classifier;
        lastBreathing = BreathingAnalyzer.fromSounds(
                new BreathSoundAnalyzer.Result(BreathSoundAnalyzer.State.LISTENING, 0), profile.ageYears);
        lastAssessment = assessmentEngine.generatePreliminaryAssessment(lastBreathing, null, profile);
    }

    /** Earlier sessions' coughs and wheezes, which decide whether the cough lasts and the wheezing recurs. */
    private void loadCoughHistory() {
        if (userId == null) return;
        new Thread(() -> {
            try {
                List<SessionEntity> earlier = AppDatabase.getInstance(ctx).sessionDao()
                        .getSinceForUser(userId, sessionStartMillis - CoughHistory.LOOKBACK_MS);
                priorWheezeTimes = CoughHistory.wheezeTimesBefore(earlier, sessionStartMillis);
                priorCoughTimes = CoughHistory.coughTimesBefore(earlier, sessionStartMillis);
            } catch (RuntimeException e) {
                Log.e(TAG, "Loading the cough history failed", e);
            }
        }, "cough-history").start();
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
    }

    public int[] recentSoundLevels() {
        int[] out = new int[liveSoundLevels.size()];
        int i = 0;
        for (Integer v : liveSoundLevels) out[i++] = v;
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
        s.pattern = lastBreathing.pattern;
        s.breathingInsight = lastBreathing.insight;
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
        stopDeviceSearch();
        if (ble != null) ble.disconnect();
        // Let the audio thread classify the sounds it still holds and judge the whole session's
        // breathing, then finish on the main thread
        runOnAudioThread(() -> {
            coughDetector.flush(soundCallback);
            classifyPendingSounds(true);
            BreathSoundAnalyzer.Result breathing = breathSound.analyzeAll();
            main.post(() -> finishAndSave(breathing));
        });
    }

    /** Stops recording without saving. */
    public void discard() {
        if (sessionEnding) return;
        sessionEnding = true;
        stopDeviceSearch();
        if (ble != null) ble.disconnect();
        finish(null, "Session discarded. Nothing was saved.");
    }

    private void finishAndSave(BreathSoundAnalyzer.Result breathingSounds) {
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

        // Pattern of the whole session's breathing sounds. Its estimated rate is kept only so the
        // assessment can be recalculated after a cough review; it is never shown.
        BreathingAnalyzer.BreathingResult sessionBreathing = BreathingAnalyzer.forSession(breathingSounds, profile.ageYears);
        int avgBpm = sessionBreathing.bpm;

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
        CoughReview.Outcome review = CoughReview.evaluate(events, avgBpm, profile,
                priorCoughTimes, priorWheezeTimes, endMillis, ZoneId.systemDefault());
        AssessmentEngine.AssessmentResult assessment = review.assessment;
        String[] trace = buildPressureTrace();

        SessionEntity s = new SessionEntity(UUID.randomUUID().toString());
        s.userId = userId;
        s.startTimeMillis = sessionStartMillis;
        s.endTimeMillis = endMillis;
        // Strongest real inhalation, so a single spike, an exhalation or a cough cannot set it
        s.peakPressureKpa = inhalationTracker.getPeakEffortKpa();
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
            closeDebugAudio();
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
     * only frequent breathing-rate readings are capped (none are logged while the rate is hidden).
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

        logPressureDebug(arrival, kpaSamples);
        trackSequence(arrival, sequence);
        // Still recorded and saved with the session, but not shown or used for the breathing
        // pattern for now (the pressure sensor is not reliable yet)
        inhalationTracker.addSamples(kpaSamples, arrival);

        // The packet's last sample arrives with it; earlier samples are one sample period apart
        float msPerSample = inhalationTracker.msPerSample();
        int n = kpaSamples.length;
        for (int i = 0; i < n; i++) {
            long sampleTime = arrival - Math.round((n - 1 - i) * msPerSample);
            recordSample(kpaSamples[i], sampleTime);
        }
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

    /** A new live breathing pattern from the breathing sounds (posted every few seconds of audio). */
    private void onBreathSounds(BreathSoundAnalyzer.Result result) {
        if (sessionEnding) return;
        lastBreathing = BreathingAnalyzer.fromSounds(result, profile.ageYears);
        runAiAnalysis();
    }

    private void runAiAnalysis() {
        long now = System.currentTimeMillis();
        // The same signs as the saved session: all of this session's coughs and wheezes so far
        lastAssessment = assessmentEngine.assess(lastBreathing,
                CoughEvidence.from(events, priorCoughTimes, priorWheezeTimes, now, ZoneId.systemDefault()), profile);

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

    /** Handles one classified 2-second window of the continuous stream: watches for wheezing. */
    private void onClipClassified(AiCoughClassifier.Result result, long clipTimeMillis) {
        if (sessionEnding) return;
        clipsClassified++;
        if (result.isConfidentWheeze()) onWheezeHeard(result, clipTimeMillis);
        runAiAnalysis();
    }

    /**
     * A sound the microphone sensed, classified by the trained model. It is recorded as a cough
     * only when a cough type is the top class and the model is at least 60% sure it is a cough
     * (AiCoughClassifier.MIN_CONFIDENCE); that percentage is shown, with the type from
     * Result.coughType(). Wheezing is logged only at its own minimum confidence and
     * confirmation, as in the continuous wheeze check; anything else is not a cough.
     */
    private void onSensedSoundClassified(AiCoughClassifier.Result result, long soundTimeMillis) {
        if (finished) return;
        if (result.isConfidentCough()) {
            CoughClass type = result.coughType();
            addEvent(new SessionEvent(soundTimeMillis, SessionEvent.Type.COUGH, result.coughPercent(), type.label));
            countedCoughs++;
            predictedCough = type;
            aiCoughLabel = type.displayText(result.coughPercent());
        } else if (result.isConfidentWheeze()) {
            onWheezeHeard(result, soundTimeMillis);
        }
        if (!sessionEnding) runAiAnalysis();
    }

    /** A confident wheeze detection: logged once a second one confirms it (WHEEZE_CONFIRM_*). */
    private void onWheezeHeard(AiCoughClassifier.Result result, long timeMillis) {
        boolean confirmed = false;
        for (long t : wheezeCandidates) {
            long gap = Math.abs(timeMillis - t);
            if (gap >= WHEEZE_CONFIRM_MIN_MS && gap <= WHEEZE_CONFIRM_MAX_MS) confirmed = true;
        }
        wheezeCandidates.addLast(timeMillis);
        while (wheezeCandidates.size() > 8) wheezeCandidates.removeFirst();
        if (confirmed) onWheeze(result, timeMillis);
    }

    private void onWheeze(AiCoughClassifier.Result result, long timeMillis) {
        if (Math.abs(timeMillis - lastWheezeEventMillis) > WHEEZE_REPEAT_MS) {
            addEvent(new SessionEvent(timeMillis, SessionEvent.Type.WHEEZE, result.percent(), result.type.label));
        }
        lastWheezeEventMillis = timeMillis;
        predictedCough = CoughClass.WHEEZING;
        aiCoughLabel = CoughClass.WHEEZING.displayText(result.percent());
    }

    private void onSoundLevel(int levelPercent) {
        liveSoundLevels.addLast(levelPercent);
        if (liveSoundLevels.size() > LIVE_SOUND_BARS) liveSoundLevels.removeFirst();
        if (listener != null) listener.onSoundLevel(levelPercent);
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

    /** Sound levels for the live chart, and each sensed cough-like sound for classification. */
    private final CoughDetector.Callback soundCallback = new CoughDetector.Callback() {
        @Override
        public void onFrameLevel(float levelDb) {
            levelBarMaxDb = Math.max(levelBarMaxDb, levelDb);
            if (++levelBarFrames < LEVEL_FRAMES_PER_BAR) return;
            int percent = Math.round((levelBarMaxDb - LEVEL_BAR_MIN_DB) / -LEVEL_BAR_MIN_DB * 100);
            final int bar = Math.max(0, Math.min(100, percent));
            levelBarMaxDb = CoughDetector.SILENCE_DB;
            levelBarFrames = 0;
            main.post(() -> onSoundLevel(bar));
        }

        @Override
        public void onCough(CoughDetector.Cough sound) {
            pendingSounds.addLast(sound);
        }
    };

    /** Wall-clock time of a stream sample: the newest sample arrived with the latest packet. */
    private long sampleTimeMillis(long sample) {
        return lastAudioArrivalMillis - Math.round((recentAudio.totalSamples() - sample) / AUDIO_SAMPLES_PER_MS);
    }

    /**
     * First sample of the 2-second clip the model hears for a sensed sound: the clip ends
     * {@link #CLIP_TAIL_SAMPLES} after the sound, so it is classified right after the sound ends
     * instead of a second later. On recorded coughs the model's scores were no worse with this
     * placement than with the sound centred in the clip. A sound too long to fit is centred on
     * its loudest moment instead.
     */
    static long clipStartFor(CoughDetector.Cough sound) {
        long start = sound.endSample + CLIP_TAIL_SAMPLES - MfccExtractor.CLIP_SAMPLES;
        return start <= sound.startSample ? start : sound.peakSample - MfccExtractor.CLIP_SAMPLES / 2;
    }

    /**
     * Runs the model on each sensed sound's clip as soon as its audio has arrived (at the end of
     * the session: now, padded with silence).
     */
    private void classifyPendingSounds(boolean sessionEnd) {
        while (!pendingSounds.isEmpty()) {
            CoughDetector.Cough sound = pendingSounds.peekFirst();
            long clipStart = clipStartFor(sound);
            if (!sessionEnd && recentAudio.totalSamples() < clipStart + MfccExtractor.CLIP_SAMPLES) return;
            pendingSounds.removeFirst();
            if (coughClassifier == null) continue; // without the model a sound cannot be confirmed as a cough

            long t0 = System.currentTimeMillis();
            AiCoughClassifier.Result result;
            try {
                result = coughClassifier.classify(recentAudio.clip(clipStart, MfccExtractor.CLIP_SAMPLES));
            } catch (RuntimeException e) {
                Log.e(TAG, "Cough model failed on a sensed sound", e);
                continue;
            }
            long now = System.currentTimeMillis();
            Log.d(TAG, "Sensed sound -> " + result.type.label + " (" + result.percent() + "%, cough total "
                    + result.coughPercent() + "%" + (result.isConfidentCough() ? " -> " + result.coughType().label : "")
                    + "), model "
                    + (now - t0) + " ms, " + (now - sampleTimeMillis(sound.endSample)) + " ms after the sound ended");
            // The breathing pattern skips counted coughs (a cough is not a breath)
            if (result.isConfidentCough()) breathSound.addCough(sound.startSample);
            long soundTime = sampleTimeMillis(sound.startSample);
            main.post(() -> onSensedSoundClassified(result, soundTime));
        }
    }

    /**
     * Debug builds only: once a second, logs the raw kPa range since the last line (tag
     * "MonitoringSession", filter "PressureDebug"). Suction is negative, so the min is the
     * strongest inhalation; compare it with the threshold to tune it.
     */
    private void logPressureDebug(long arrival, float[] kpaSamples) {
        if ((ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        for (float v : kpaSamples) {
            if (v < pressureDebugMin) pressureDebugMin = v;
            if (v > pressureDebugMax) pressureDebugMax = v;
            pressureDebugSum += v;
        }
        pressureDebugCount += kpaSamples.length;
        if (arrival - pressureDebugSinceMillis < PRESSURE_DEBUG_LOG_MS) return;
        Log.d(TAG, String.format(java.util.Locale.US,
                "PressureDebug n=%d min=%.3f max=%.3f mean=%.3f kPa (inhale threshold -%.2f)",
                pressureDebugCount, pressureDebugMin, pressureDebugMax,
                pressureDebugSum / pressureDebugCount, BreathingAnalyzer.INHALATION_THRESHOLD_KPA));
        pressureDebugSinceMillis = arrival;
        pressureDebugMin = Float.MAX_VALUE;
        pressureDebugMax = -Float.MAX_VALUE;
        pressureDebugSum = 0;
        pressureDebugCount = 0;
    }

    /** Debug builds only: keeps the first 5 minutes of the session's microphone audio in app storage. */
    private void writeDebugAudio(byte[] audioBytes) {
        if ((ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        if (debugAudioBytes >= DEBUG_AUDIO_MAX_BYTES) return;
        try {
            if (debugAudio == null) debugAudio = openDebugAudio();
            debugAudio.write(audioBytes);
            debugAudioBytes += audioBytes.length;
            if (debugAudioBytes >= DEBUG_AUDIO_MAX_BYTES) closeDebugAudio();
        } catch (IOException e) {
            Log.w(TAG, "Debug audio recording failed", e);
            debugAudioBytes = DEBUG_AUDIO_MAX_BYTES;
            closeDebugAudio();
        }
    }

    /** A new file for this session; only the newest {@link #DEBUG_AUDIO_KEEP} sessions are kept. */
    private FileOutputStream openDebugAudio() throws IOException {
        File dir = new File(ctx.getFilesDir(), DEBUG_AUDIO_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File[] old = dir.listFiles();
        if (old != null && old.length >= DEBUG_AUDIO_KEEP) {
            Arrays.sort(old, (a, b) -> a.getName().compareTo(b.getName()));   // names sort by start time
            for (int i = 0; i <= old.length - DEBUG_AUDIO_KEEP; i++) old[i].delete();
        }
        return new FileOutputStream(new File(dir, "session_" + sessionStartMillis + ".pcm"));
    }

    private void closeDebugAudio() {
        if (debugAudio == null) return;
        try {
            debugAudio.close();
        } catch (IOException ignored) {
        }
        debugAudio = null;
    }


    /** Feeds the breathing-pattern analysis and posts a new live pattern every LIVE_BREATH_EVERY_S. */
    private void updateBreathSounds(byte[] audioBytes) {
        breathSound.addPcm(audioBytes);
        breathSamplesSinceReading += audioBytes.length / 2;
        if (breathSamplesSinceReading < (long) LIVE_BREATH_EVERY_S * BreathSoundAnalyzer.SAMPLE_RATE) return;
        breathSamplesSinceReading = 0;
        BreathSoundAnalyzer.Result result = breathSound.analyzeRecent(LIVE_BREATH_WINDOW_S);
        main.post(() -> onBreathSounds(result));
    }

    /**
     * Sensed sounds are classified as described in {@link #onSensedSoundClassified}; in addition
     * every 2-second window of the stream, stepping 1 second at a time, is checked for wheezing.
     */
    private void processAudio(byte[] audioBytes, long arrivalMillis) {
        lastAudioArrivalMillis = arrivalMillis;
        writeDebugAudio(audioBytes);
        recentAudio.write(audioBytes);
        coughDetector.process(audioBytes, soundCallback);
        classifyPendingSounds(false);
        updateBreathSounds(audioBytes);

        if (coughClassifier == null) return;
        audioWindow.write(audioBytes, 0, audioBytes.length);

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

            main.post(() -> onClipClassified(result, clipTimeMillis));
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
            // Never reached this session: the saved address may belong to an old board (a new ESP32
            // has a different MAC), so look for whichever TANGHAP device is advertising nearby
            if (!everConnected && ble.isBluetoothEnabled() && ble.hasScanPermission()) {
                startDeviceSearch();
                return;
            }
            // No attempt started (e.g. Bluetooth off): no callback will come, so retry again
            if (!ble.connect(deviceAddress)) scheduleReconnect();
        }, delay);
    }

    private void startDeviceSearch() {
        searchingForDevice = true;
        ble.startScan();
        main.postDelayed(endDeviceSearch, DEVICE_SEARCH_MS);
    }

    private void stopDeviceSearch() {
        main.removeCallbacks(endDeviceSearch);
        if (searchingForDevice && ble != null) ble.stopScan();
        searchingForDevice = false;
    }

    /** Nothing found in time: stop scanning and try again later. */
    private void endDeviceSearch() {
        if (!searchingForDevice) return;
        stopDeviceSearch();
        scheduleReconnect();
    }

    @Override
    public void onScanResults(List<BleDeviceItem> results) {
        main.post(() -> {
            if (!searchingForDevice || sessionEnding || results.isEmpty()) return;
            // The saved device if it is advertising, then the known TANGHAP board, otherwise the closest one
            BleDeviceItem pick = null;
            BleDeviceItem known = null;
            for (BleDeviceItem d : results) {
                if (d.address.equalsIgnoreCase(deviceAddress)) {
                    pick = d;
                    known = null;
                    break;
                }
                if (d.address.equalsIgnoreCase(BleManager.TANGHAP_DEVICE_ADDRESS)) known = d;
                if (pick == null || d.rssi > pick.rssi) pick = d;
            }
            if (known != null) pick = known;
            stopDeviceSearch();
            if (!pick.address.equalsIgnoreCase(deviceAddress)) {
                Log.i(TAG, "Saved device " + deviceAddress + " not found; switching to " + pick.address);
                message("Found TANGHAP device " + pick.address + ". Connecting...");
                deviceAddress = pick.address;
            }
            if (!ble.connect(deviceAddress)) scheduleReconnect();
        });
    }

    @Override
    public void onBattery(Integer percent) {
        if (percent == null || percent > 100) return;
        android.content.SharedPreferences prefs =
                ctx.getSharedPreferences(HealthPrefs.SESSION_PREFS, Context.MODE_PRIVATE);
        // Tell the user to charge the device once at 20% and once more at 10%
        int announced = prefs.getInt(HealthPrefs.KEY_BATTERY_ALERT_LEVEL, BatteryAlert.LEVEL_NONE);
        int level = BatteryAlert.toAnnounce(announced, percent);
        prefs.edit()
                .putInt("last_battery", percent)
                .putLong(HealthPrefs.KEY_LAST_BATTERY_TIME, System.currentTimeMillis())
                .putInt(HealthPrefs.KEY_BATTERY_ALERT_LEVEL, BatteryAlert.remember(announced, percent))
                .apply();
        if (level != BatteryAlert.LEVEL_NONE) {
            NotificationHelper.show(ctx, NotificationHelper.ID_BATTERY, BatteryAlert.title(level),
                    BatteryAlert.message(level), new Intent(ctx, com.sti.tanghap.ui.DashboardActivity.class));
        }
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
