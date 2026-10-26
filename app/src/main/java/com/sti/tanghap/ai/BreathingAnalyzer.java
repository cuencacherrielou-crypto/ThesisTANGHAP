package com.sti.tanghap.ai;

import com.sti.tanghap.domain.InhalationTracker;
import com.sti.tanghap.domain.RespiratoryNorms;

import java.util.ArrayList;
import java.util.List;

/**
 * AI-assisted breathing pattern analysis based on pressure sensor fluctuations.
 */
public class BreathingAnalyzer {

    /** Suction (kPa below rest; the device sends it as negative kPa) that counts as an inhalation. */
    public static final float INHALATION_THRESHOLD_KPA = 0.3f;
    /** Fewer breaths than this give no reliable rate, so no BPM is reported yet. */
    public static final int MIN_BREATHS_FOR_RATE = 3;
    /** With no breath for this long (or twice the usual spacing, if longer) the rate is stale. */
    private static final float STALE_AFTER_SEC = 10f;
    /** Two breaths closer than this are treated as one (caps detection at 60 BPM). */
    private static final float MIN_BREATH_SPACING_SEC = 1.0f;

    public static class BreathingResult {
        public int bpm;
        public String pattern;
        public String insight;
        public String severity; // Normal, Low, High

        public BreathingResult(int bpm, String pattern, String insight, String severity) {
            this.bpm = bpm;
            this.pattern = pattern;
            this.insight = insight;
            this.severity = severity;
        }
    }

    /**
     * Analyzes pressure sensor data to compute breathing rate and pattern.
     *
     * @param pressureData Pressure values in kPa from the ESP32, in arrival order
     * @param durationSec  Real time in seconds covered by {@code pressureData}
     * @return BreathingResult containing BPM and insights
     */
    public BreathingResult analyze(float[] pressureData, float durationSec) {
        return analyze(pressureData, durationSec, -1);
    }

    /** Same as {@link #analyze(float[], float)}, judging the rate against the user's age. */
    public BreathingResult analyze(float[] pressureData, float durationSec, int ageYears) {
        if (pressureData == null || pressureData.length < 3 || durationSec <= 0) {
            return new BreathingResult(0, "Awaiting Inhalation...", "No pressure data available.", "Normal");
        }
        float sampleRateHz = pressureData.length / durationSec;
        int minDistance = Math.max(1, Math.round(sampleRateHz * MIN_BREATH_SPACING_SEC));
        List<Integer> starts = inhalationStarts(pressureData, minDistance);
        int breaths = starts.size();

        if (breaths == 0) {
            return new BreathingResult(0, "Awaiting Inhalation...",
                    "Inhale through the mouthpiece to measure breathing rate.", "Normal");
        }
        if (breaths < MIN_BREATHS_FOR_RATE) {
            // One inhaler puff, or the first breaths of a session: a rate would be meaningless
            return new BreathingResult(0, "Measuring breathing rate...",
                    breaths + " of " + MIN_BREATHS_FOR_RATE + " breaths so far. Keep breathing normally "
                            + "through the mouthpiece to measure the rate.", "Normal");
        }

        // Rate from the spacing of the breaths themselves, not from how long the window is, so
        // quiet time before the first breath does not make breathing look slow
        float spanSec = (starts.get(breaths - 1) - starts.get(0)) / sampleRateHz;
        float meanIntervalSec = spanSec / (breaths - 1);
        float sinceLastSec = (pressureData.length - 1 - starts.get(breaths - 1)) / sampleRateHz;
        if (sinceLastSec > Math.max(STALE_AFTER_SEC, 2 * meanIntervalSec)) {
            return new BreathingResult(0, "Breathing paused",
                    "No breath in the last " + Math.round(sinceLastSec) + " seconds.", "Normal");
        }
        int bpm = Math.round(60f / meanIntervalSec);

        return classify(bpm, ageYears);
    }

    /**
     * Counts breaths in as the inhalations in the data: suction (negative kPa from the device)
     * reaching the threshold. Exhaling or coughing into the mouthpiece reads positive and is not
     * counted, so a breath in and out counts once. The inhalation ends when suction falls below
     * a quarter of the threshold (as in InhalationTracker), so a wavering breath is not counted
     * twice; inhalations closer together than {@code minDistance} samples count as one.
     * Sensor glitches (a frozen value or the sensor's limit, see
     * {@link InhalationTracker#isSensorGlitch}) are not breaths.
     */
    public static int countInhalations(float[] kpa, int minDistance) {
        return inhalationStarts(kpa, minDistance).size();
    }

    /** Sample index where each counted inhalation reaches the threshold, oldest first. */
    public static List<Integer> inhalationStarts(float[] kpa, int minDistance) {
        float release = INHALATION_THRESHOLD_KPA * InhalationTracker.ONSET_FRACTION;
        List<Integer> starts = new ArrayList<>();
        int lastStart = -minDistance;
        int runStart = -1;
        float runFirst = 0f, runPeak = 0f;
        boolean runVaries = false;
        for (int i = 0; i <= kpa.length; i++) {
            float suction = i < kpa.length ? -kpa[i] : 0f;   // the data's end closes an open run
            if (runStart < 0) {
                if (i < kpa.length && suction >= INHALATION_THRESHOLD_KPA) {
                    runStart = i;
                    runFirst = suction;
                    runPeak = suction;
                    runVaries = false;
                }
            } else if (suction < release || i == kpa.length) {
                if (!InhalationTracker.isSensorGlitch(runVaries, runPeak)) {
                    if (runStart - lastStart >= minDistance) starts.add(runStart);
                    lastStart = runStart;
                }
                runStart = -1;
            } else {
                if (suction != runFirst) runVaries = true;
                if (suction > runPeak) runPeak = suction;
            }
        }
        return starts;
    }

    /**
     * Breathing pattern from the breathing sounds ({@link BreathSoundAnalyzer}), which replaces the
     * pressure-based rate for now. Only the pattern and its confidence are shown; the estimated
     * rate stays internal. Only a high-confidence pattern is used for the advice (its bpm is
     * passed on); a medium or low one is shown for information with bpm 0, as not measured.
     * A low-confidence "normal" is not shown at all: on held-out recordings it was almost never
     * right (fast or slow breathing counted at double or half its rate).
     */
    public static BreathingResult fromSounds(BreathSoundAnalyzer.Result sound, int ageYears) {
        switch (sound.state) {
            case MEASURED: {
                BreathSoundAnalyzer.Confidence confidence = sound.confidence();
                BreathingResult r = classify((int) Math.round(sound.rateBpm), ageYears);
                if (confidence == BreathSoundAnalyzer.Confidence.LOW && "Normal".equals(r.severity)) {
                    return new BreathingResult(0, NOT_CLEAR_PATTERN,
                            "Breathing in and out could not be told apart, so the pattern is not shown. "
                                    + "Breathe normally toward the device, without talking.", "Normal");
                }
                r.pattern += " · " + CONFIDENCE_TEXT[confidence.ordinal()];
                if (confidence == BreathSoundAnalyzer.Confidence.HIGH) {
                    r.insight += " Estimated from breathing sounds, high confidence.";
                } else {
                    r.insight += " Estimated from breathing sounds, " + CONFIDENCE_TEXT[confidence.ordinal()].toLowerCase()
                            + " (breathing in and out sounded alike), so it is shown for information only and "
                            + "not used for the advice. Breathing toward the device gives a surer reading.";
                    r.bpm = 0;
                }
                return r;
            }
            case NOT_CLEAR:
                return new BreathingResult(0, NOT_CLEAR_PATTERN,
                        "Breathing was too faint or not steady enough to judge. Breathe normally toward "
                                + "the device, without talking.", "Normal");
            default:
                return new BreathingResult(0, LISTENING_PATTERN,
                        "Keep breathing normally toward the device; the pattern shows after about "
                                + BreathSoundAnalyzer.MIN_LISTEN_S + " seconds.", "Normal");
        }
    }

    /** The saved session's pattern: like {@link #fromSounds}, but a session too short to judge says so. */
    public static BreathingResult forSession(BreathSoundAnalyzer.Result sound, int ageYears) {
        if (sound.state == BreathSoundAnalyzer.State.LISTENING) {
            return new BreathingResult(0, NOT_MEASURED_PATTERN, "The session was shorter than "
                    + BreathSoundAnalyzer.MIN_LISTEN_S + " seconds, too short to judge the breathing pattern.", "Normal");
        }
        return fromSounds(sound, ageYears);
    }

    /** One word for a saved pattern (Normal / Rapid / Slow), or "--" when it was not measured. */
    public static String shortLabel(String pattern) {
        if (pattern == null) return "--";
        if (pattern.startsWith("Rapid")) return "Rapid";
        if (pattern.startsWith("Slow")) return "Slow";
        if (pattern.startsWith("Normal")) return "Normal";
        return "--";
    }

    /** In {@link BreathSoundAnalyzer.Confidence} order. */
    private static final String[] CONFIDENCE_TEXT = {"High confidence", "Medium confidence", "Low confidence"};

    public static final String LISTENING_PATTERN = "Listening to breathing...";
    public static final String NOT_CLEAR_PATTERN = "Breathing not clear";
    public static final String NOT_MEASURED_PATTERN = "Breathing not measured";

    /** Pattern classification for a breathing rate, using adult limits (12-20 BPM). */
    public static BreathingResult classify(int bpm) {
        return classify(bpm, -1);
    }

    /**
     * Pattern classification for a breathing rate (also used for the session average).
     *
     * @param ageYears the user's age; negative when unknown (adult limits are used)
     */
    public static BreathingResult classify(int bpm, int ageYears) {
        String pattern;
        String insight;
        String severity;
        String range = RespiratoryNorms.rangeText(ageYears);

        if (bpm <= 0) {
            return new BreathingResult(0, "Awaiting Inhalation...", "Inhale through the mouthpiece to measure breathing rate.", "Normal");
        } else if (RespiratoryNorms.isAboveNormal(bpm, ageYears)) {
            pattern = "Rapid Breathing (Tachypnea-like)";
            insight = "Breathing is faster than the normal " + range + " range. This can happen with exertion, "
                    + "and there is a possibility of respiratory distress.";
            severity = "High";
        } else if (RespiratoryNorms.isBelowNormal(bpm, ageYears)) {
            pattern = "Slow Breathing (Bradypnea-like)";
            insight = "Breathing is slower than the normal " + range + " range. This can happen with deep relaxation, "
                    + "and there is a possibility of suppressed breathing.";
            severity = "Low";
        } else {
            pattern = "Normal Breathing";
            insight = "Respiratory rhythm is within the normal range (" + range + ").";
            severity = "Normal";
        }

        return new BreathingResult(bpm, pattern, insight, severity);
    }
}
