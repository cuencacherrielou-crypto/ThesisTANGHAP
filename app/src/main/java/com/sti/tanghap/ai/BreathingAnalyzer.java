package com.sti.tanghap.ai;

import com.sti.tanghap.domain.RespiratoryNorms;

/**
 * Breathing pattern (normal, rapid or slow) from the breathing sounds heard by the microphone
 * ({@link BreathSoundAnalyzer}), judged against the normal breathing-rate range for the user's age.
 */
public class BreathingAnalyzer {

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
     * Breathing pattern from the breathing sounds ({@link BreathSoundAnalyzer}). Only the pattern
     * and its confidence are shown; the estimated
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
            return new BreathingResult(0, NOT_MEASURED_PATTERN,
                    "The breathing pattern was not measured clearly, so it is not used for the advice.", "Normal");
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
