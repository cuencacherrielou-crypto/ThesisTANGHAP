package com.sti.tanghap.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Breathing pattern from the device microphone (the pressure sensor is not used). A port of
 * tools/breath_detect.py, which was tested on breathing recordings found online and on TANGHAP
 * device sessions; the app shows only the pattern it gives (normal / rapid / slow), never a number.
 *
 * 1. Level of the 150-3000 Hz band in 50 ms frames (breath sound is broadband airflow noise).
 * 2. Sound segments: frames above the noise floor + ON_DB (hysteresis), short gaps merged; a
 *    segment is also split at a clear dip (a phase turn inside continuous breathing).
 * 3. Coughs (the model's counted coughs) and voiced sounds (talking, humming) are dropped.
 * 4. Which segments are breaths: loud/quiet alternation (breathing toward the device makes one
 *    phase ~20 dB louder) -> the loud ones; inhale and exhale alternating in length, pause or
 *    loudness -> every other one; otherwise each one.
 * 5. Rate from the median breath interval of the stretch (whole recording or any 30 s window) with
 *    the most intervals agreeing with that median, among stretches where at least 60% agree. No
 *    rate when there is none: then the breathing was too faint or not steady enough to judge.
 *
 * Audio is fed as it arrives (16 kHz 16-bit mono); not thread-safe (use from the audio thread).
 */
public class BreathSoundAnalyzer {

    public static final int SAMPLE_RATE = 16_000;
    static final int FPS = 20;
    private static final int HOP = SAMPLE_RATE / FPS;          // 50 ms frame
    private static final int WIN = 2 * HOP;                    // pitch window (100 ms)

    // Constants as in breath_detect.py (tuned on tuning people, tested on held-out people)
    static final double ON_DB = 4.0;           // a sound starts this far above the noise floor
    static final double OFF_DB = 3.0;          // and ends below floor + OFF_DB
    static final double MERGE_GAP_S = 0.2;
    static final double MIN_SEG_S = 0.25;
    static final double DIP_DB = 5.0;          // deep dip inside a segment that marks a phase turn
    static final double ALT_DB = 6.0;          // loud/quiet difference that marks the two phases
    static final double ALT_CORR = 0.3;        // lag-1 correlation this negative = alternating phases
    static final double MAX_BPM = 45;          // one segment per breath above this is implausible
    static final double SMOOTH_S = 0.2;        // level smoothing (airflow flutters by > 6 dB)
    static final double VOICE_R = 0.5;         // pitch strength of a voiced frame
    static final double VOICED_MIN = 0.5;      // segment with this fraction voiced = voice, not breath
    static final double COUGH_BEFORE_S = 0.3;  // a cough covers [start - before, start + after]
    static final double COUGH_AFTER_S = 1.0;
    static final double STEADY_TOL = 0.35;     // interval this close to the median agrees with it
    static final double STEADY_MIN = 0.6;      // fraction of intervals that must agree
    static final int STEADY_MIN_IV = 3;        // and at least this many agreeing intervals
    static final int WINDOW_S = 30;            // stretch length searched besides the whole recording
    static final double MIN_IV_S = 60.0 / 45, MAX_IV_S = 60.0 / 4;

    /** Frames kept (20 min); older ones are dropped 5 min at a time. */
    private static final int MAX_FRAMES = 20 * 60 * FPS;
    private static final int DROP_FRAMES = 5 * 60 * FPS;

    // 4th-order Butterworth band-passes at 16 kHz as second-order sections {b0, b1, b2, a1, a2}
    // (scipy.signal.butter(4, band, 'bandpass', fs=16000, output='sos'))
    private static final double[][] LEVEL_BAND = {   // 150-3000 Hz
            {0.03225638534071301, 0.06451277068142602, 0.03225638534071301, -0.4942654114145001, 0.10346692521215511},
            {1.0, 2.0, 1.0, -0.5917250536550724, 0.5087408637837407},
            {1.0, -2.0, 1.0, -1.8867335340739597, 0.8905724083318101},
            {1.0, -2.0, 1.0, -1.9552505608054547, 0.958724606732832}};
    private static final double[][] VOICE_BAND = {   // 300-2000 Hz, above the device's low rumble
            {0.005925858509277193, 0.011851717018554387, 0.005925858509277193, -1.1005981145264563, 0.35081278538838706},
            {1.0, 2.0, 1.0, -1.196151908356285, 0.6620770867450028},
            {1.0, -2.0, 1.0, -1.7555236366748068, 0.7761699587764924},
            {1.0, -2.0, 1.0, -1.9177088882051445, 0.9317999102715666}};
    private static final int PITCH_LAG_MIN = SAMPLE_RATE / 400;   // 400 Hz
    private static final int PITCH_LAG_MAX = SAMPLE_RATE / 70;    // 70 Hz
    private static final double[] HANN = new double[WIN];
    /** Overlap of the window with itself at each pitch lag, so long lags are not penalised. */
    private static final double[] OVERLAP = new double[PITCH_LAG_MAX + 1];

    static {
        for (int i = 0; i < WIN; i++) HANN[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WIN - 1));
        double total = 0;
        for (double w : HANN) total += w * w * w * w;
        for (int lag = 0; lag <= PITCH_LAG_MAX; lag++) {
            double s = 0;
            for (int i = 0; i + lag < WIN; i++) s += HANN[i] * HANN[i] * HANN[i + lag] * HANN[i + lag];
            OVERLAP[lag] = s / total;
        }
    }

    /** What the analysis found. */
    public enum State {
        /** Less than {@link #MIN_LISTEN_S} of audio so far. */
        LISTENING,
        /** Breathing too faint or not steady enough to judge (also while talking or moving). */
        NOT_CLEAR,
        /** A breathing rate was estimated; only its pattern is shown. */
        MEASURED
    }

    /** Audio needed before a pattern is attempted (one search window). */
    public static final int MIN_LISTEN_S = WINDOW_S;

    /**
     * How the breaths were told apart, which decides how far the pattern can be trusted. Label
     * right on held-out breathing recordings (people not used for tuning; tuning people in brackets):
     * LOUD_PHASE 97.7% (90.8%), TWO_PHASES 73.9% (84.2%), ONE_PHASE 43.3% (62.6%)
     * (tools/breath_conf.py). The usual mistake is counting half or double the real rate.
     */
    public enum Rule {
        /** One phase much louder than the other (breathing toward the device): high confidence. */
        LOUD_PHASE,
        /** Both phases heard, told apart by length, pause or loudness: medium confidence. */
        TWO_PHASES,
        /** One sound per breath: low confidence. */
        ONE_PHASE
    }

    public enum Confidence { HIGH, MEDIUM, LOW }

    public static final class Result {
        public final State state;
        /** Estimated breaths per minute, for classifying the pattern only (0 unless MEASURED). */
        public final double rateBpm;
        /** How the breaths were told apart (null unless MEASURED). */
        public final Rule rule;

        public Result(State state, double rateBpm) {
            this(state, rateBpm, null);
        }

        public Result(State state, double rateBpm, Rule rule) {
            this.state = state;
            this.rateBpm = rateBpm;
            this.rule = rule;
        }

        /** null unless MEASURED. */
        public Confidence confidence() {
            if (rule == null) return null;
            switch (rule) {
                case LOUD_PHASE: return Confidence.HIGH;
                case TWO_PHASES: return Confidence.MEDIUM;
                default: return Confidence.LOW;
            }
        }
    }

    private final double[][] levelZ = new double[LEVEL_BAND.length][2];
    private final double[][] voiceZ = new double[VOICE_BAND.length][2];
    private final double[] voiceBuf = new double[WIN];   // the last two hops of the voice band
    private double energy = 0;
    private int hopSamples = 0;
    private long hops = 0;                                // completed hops since the start

    private float[] level = new float[4096];
    private float[] pitch = new float[4096];
    private int frames = 0;                               // frames held
    private long firstFrame = 0;                          // absolute index of level[0]
    private final List<Double> coughs = new ArrayList<>(); // cough start times, s from the start

    /** Adds 16-bit little-endian mono PCM. */
    public void addPcm(byte[] pcm) {
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            addSample(s / 32768.0);
        }
    }

    void addSample(double x) {
        double y = filter(LEVEL_BAND, levelZ, x);
        energy += y * y;
        voiceBuf[HOP + hopSamples] = filter(VOICE_BAND, voiceZ, x);
        if (++hopSamples < HOP) return;

        // Hop complete: its level is frame `hops`; the window ending here is the pitch of frame hops-1
        if (hops > 0 && hops - 1 >= firstFrame) pitch[(int) (hops - 1 - firstFrame)] = (float) pitchStrength(voiceBuf);
        appendFrame((float) (10 * Math.log10(energy / HOP + 1e-12)));
        System.arraycopy(voiceBuf, HOP, voiceBuf, 0, HOP);
        energy = 0;
        hopSamples = 0;
        hops++;
    }

    private void appendFrame(float levelDb) {
        if (frames == MAX_FRAMES) {
            System.arraycopy(level, DROP_FRAMES, level, 0, frames - DROP_FRAMES);
            System.arraycopy(pitch, DROP_FRAMES, pitch, 0, frames - DROP_FRAMES);
            frames -= DROP_FRAMES;
            firstFrame += DROP_FRAMES;
        }
        if (frames == level.length) {
            level = Arrays.copyOf(level, Math.min(2 * frames, MAX_FRAMES));
            pitch = Arrays.copyOf(pitch, level.length);
        }
        level[frames] = levelDb;
        pitch[frames] = 0f;                                // set when the next hop completes
        frames++;
    }

    /** A counted cough starting at this sample of the stream (0 = the first sample added). */
    public void addCough(long startSample) {
        coughs.add(startSample / (double) SAMPLE_RATE);
    }

    /** Seconds of audio added so far. */
    public double seconds() {
        return hops / (double) FPS;
    }

    /** Pattern of the last {@code seconds} of audio (the live reading). */
    public Result analyzeRecent(int seconds) {
        int n = Math.min(frames, seconds * FPS);
        return analyze(frames - n, frames);
    }

    /** Pattern of everything held (the session result). */
    public Result analyzeAll() {
        return analyze(0, frames);
    }

    private Result analyze(int from, int to) {
        if (to - from < MIN_LISTEN_S * FPS) return new Result(State.LISTENING, 0);
        Result r = estimate(Arrays.copyOfRange(level, from, to), Arrays.copyOfRange(pitch, from, to),
                coughsFrom((firstFrame + from) / (double) FPS));
        return r != null ? r : new Result(State.NOT_CLEAR, 0);
    }

    private List<Double> coughsFrom(double startSec) {
        List<Double> out = new ArrayList<>();
        for (double t : coughs) out.add(t - startSec);
        return out;
    }

    // ---------------------------------------------------------------------
    // Analysis (same steps and numbers as breath_detect.py)
    // ---------------------------------------------------------------------

    /** Segment: frames [start, end) and mean smoothed level. */
    static final class Segment {
        final int start, end;
        final double level;

        Segment(int start, int end, double level) {
            this.start = start;
            this.end = end;
            this.level = level;
        }
    }

    /** Start frames of the breaths, and how they were told apart. */
    static final class Breaths {
        final int[] starts;
        final Rule rule;

        Breaths(int[] starts, Rule rule) {
            this.starts = starts;
            this.rule = rule;
        }
    }

    /**
     * Breathing rate of a level curve (dB per 50 ms frame) with its pitch strengths and the cough
     * start times (s from its first frame); null when no steady stretch is found.
     */
    static Result estimate(float[] lv, float[] pitch, List<Double> coughTimes) {
        List<Segment> segs = segments(lv);
        List<Segment> keep = new ArrayList<>();
        List<int[]> holes = new ArrayList<>();
        for (Segment s : segs) {
            double a = s.start / (double) FPS, b = s.end / (double) FPS;
            boolean cough = false;
            for (double t : coughTimes) {
                if (a < t + COUGH_AFTER_S && b > t - COUGH_BEFORE_S) cough = true;
            }
            int voiced = 0;
            for (int i = s.start; i < s.end; i++) if (pitch[i] >= VOICE_R) voiced++;
            boolean voice = voiced / (double) (s.end - s.start) >= VOICED_MIN;
            if (cough || voice) holes.add(new int[]{s.start, s.end});
            else keep.add(s);
        }

        int n = lv.length;
        List<int[]> spans = new ArrayList<>();
        spans.add(new int[]{0, n});
        for (int a = 0; a <= n - WINDOW_S * FPS; a += 5 * FPS) spans.add(new int[]{a, a + WINDOW_S * FPS});
        double bestMedian = 0, bestFrac = -1;
        Rule bestRule = null;
        int bestAgree = -1;
        for (int[] span : spans) {
            List<Segment> in = new ArrayList<>();
            for (Segment s : keep) if (span[0] <= s.start && s.start < span[1]) in.add(s);
            Breaths br = breaths(in);
            int[] starts = br.starts;
            if (starts.length < 3) continue;
            double[] d = new double[starts.length - 1];
            for (int i = 0; i < d.length; i++) d[i] = starts[i + 1] - starts[i];
            double m = median(d);
            int agree = 0;
            for (int i = 0; i < d.length; i++) {
                boolean ok = Math.abs(d[i] - m) <= STEADY_TOL * m;
                if (!ok && Math.abs(d[i] - 2 * m) <= STEADY_TOL * 2 * m) {
                    // about two breaths across a dropped cough or voice: a breath was hidden there
                    for (int[] h : holes) if (starts[i] < h[1] && h[0] < starts[i + 1]) ok = true;
                }
                if (ok) agree++;
            }
            double frac = agree / (double) d.length;
            if (agree < STEADY_MIN_IV || frac < STEADY_MIN || m < MIN_IV_S * FPS || m > MAX_IV_S * FPS) continue;
            // Most agreeing breaths wins (a short stretch of 3 stray sounds can look perfectly even)
            if (agree > bestAgree || (agree == bestAgree && frac > bestFrac)) {
                bestAgree = agree;
                bestFrac = frac;
                bestMedian = m;
                bestRule = br.rule;
            }
        }
        return bestAgree < 0 ? null : new Result(State.MEASURED, 60.0 * FPS / bestMedian, bestRule);
    }

    /** Start frames of the breaths among the segments (under 4 segments: each one, ONE_PHASE). */
    static Breaths breaths(List<Segment> segs) {
        int n = segs.size();
        int[] all = new int[n];
        for (int i = 0; i < n; i++) all[i] = segs.get(i).start;
        if (n < 4) return new Breaths(all, Rule.ONE_PHASE);
        double[] lev = new double[n], dur = new double[n], gaps = new double[n - 1];
        for (int i = 0; i < n; i++) {
            lev[i] = segs.get(i).level;
            dur[i] = segs.get(i).end - segs.get(i).start;
            if (i > 0) gaps[i - 1] = segs.get(i).start - segs.get(i - 1).end;
        }
        double[] d = new double[n - 1], absD = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            d[i] = lev[i + 1] - lev[i];
            absD[i] = Math.abs(d[i]);
        }
        int flips = 0;
        for (int i = 0; i + 1 < d.length; i++) if (Math.signum(d[i + 1]) != Math.signum(d[i])) flips++;
        double alternating = flips / (double) (d.length - 1);
        if (median(absD) >= ALT_DB && alternating >= 0.7) {
            // breathing toward the device makes one phase much louder than the other
            double thr = (percentile(lev, 75) + percentile(lev, 25)) / 2;
            List<Integer> loud = new ArrayList<>();
            for (int i = 0; i < n; i++) if (lev[i] >= thr) loud.add(all[i]);
            return new Breaths(toArray(loud), Rule.LOUD_PHASE);
        }
        double[] startGaps = new double[n - 1];
        for (int i = 0; i < n - 1; i++) startGaps[i] = all[i + 1] - all[i];
        double oneRate = 60.0 * FPS / median(startGaps);
        double alt = Math.min(lag1(lev), Math.min(lag1(dur), lag1(gaps)));
        if (alt <= -ALT_CORR || oneRate > MAX_BPM) {
            // inhale and exhale alternate (length, pause or loudness): two segments per breath
            double even = 0, odd = 0;
            int ne = 0, no = 0;
            for (int i = 0; i < n; i++) {
                if (i % 2 == 0) { even += lev[i]; ne++; } else { odd += lev[i]; no++; }
            }
            int k = even / ne >= odd / no ? 0 : 1;
            List<Integer> out = new ArrayList<>();
            for (int i = k; i < n; i += 2) out.add(all[i]);
            return new Breaths(toArray(out), Rule.TWO_PHASES);
        }
        return new Breaths(all, Rule.ONE_PHASE);
    }

    /** Sound segments of a level curve. */
    static List<Segment> segments(float[] raw) {
        double[] lv = smooth(raw);
        double[] fl = noiseFloor(lv);
        List<int[]> segs = new ArrayList<>();
        boolean on = false;
        int start = 0;
        for (int i = 0; i < lv.length; i++) {
            if (!on && lv[i] > fl[i] + ON_DB) {
                on = true;
                start = i;
            } else if (on && lv[i] < fl[i] + OFF_DB) {
                on = false;
                segs.add(new int[]{start, i});
            }
        }
        if (on) segs.add(new int[]{start, lv.length});
        List<int[]> merged = new ArrayList<>();
        for (int[] s : segs) {
            if (!merged.isEmpty() && s[0] - merged.get(merged.size() - 1)[1] <= MERGE_GAP_S * FPS) {
                merged.get(merged.size() - 1)[1] = s[1];
            } else {
                merged.add(s);
            }
        }
        List<Segment> out = new ArrayList<>();
        for (int[] m : merged) {
            int a = m[0], b = m[1];
            List<Integer> edges = new ArrayList<>();
            edges.add(a);
            if (b - a > 2 * MIN_SEG_S * FPS) {
                // split only at a deep dip (inhale running straight into exhale without a pause)
                double[] neg = new double[b - a];
                for (int i = 0; i < neg.length; i++) neg[i] = -lv[a + i];
                for (int c : findPeaks(neg, DIP_DB, (int) (0.45 * FPS))) {
                    if (c >= MIN_SEG_S * FPS && neg.length - c >= MIN_SEG_S * FPS) edges.add(a + c);
                }
            }
            edges.add(b);
            for (int i = 0; i + 1 < edges.size(); i++) {
                int s = edges.get(i), e = edges.get(i + 1);
                if (e - s >= MIN_SEG_S * FPS) {
                    double sum = 0;
                    for (int j = s; j < e; j++) sum += lv[j];
                    out.add(new Segment(s, e, sum / (e - s)));
                }
            }
        }
        return out;
    }

    /** Moving mean over SMOOTH_S, as numpy 'same' convolution but averaging only frames that exist. */
    static double[] smooth(float[] lv) {
        int k = Math.max(1, (int) (SMOOTH_S * FPS));
        int ahead = (k - 1) / 2;
        double[] out = new double[lv.length];
        for (int i = 0; i < lv.length; i++) {
            int lo = Math.max(0, i + ahead - (k - 1)), hi = Math.min(lv.length - 1, i + ahead);
            double s = 0;
            for (int j = lo; j <= hi; j++) s += lv[j];
            out[i] = s / (hi - lo + 1);
        }
        return out;
    }

    /** 10th percentile over a sliding 10 s window (the quiet between breaths), per second. */
    static double[] noiseFloor(double[] lv) {
        int w = 10 * FPS;
        double[] out = new double[lv.length];
        for (int i = 0; i < lv.length; i += FPS) {
            int a = Math.max(0, i - w / 2), b = Math.min(lv.length, i + w / 2);
            double p = percentile(Arrays.copyOfRange(lv, a, b), 10);
            for (int j = i; j < Math.min(lv.length, i + FPS); j++) out[j] = p;
        }
        return out;
    }

    /**
     * Peaks of x with at least this prominence, closer peaks than {@code distance} removed
     * (smaller first), as scipy.signal.find_peaks.
     */
    static List<Integer> findPeaks(double[] x, double prominence, int distance) {
        List<Integer> peaks = new ArrayList<>();
        int i = 1;
        while (i < x.length - 1) {
            if (x[i - 1] < x[i]) {
                int ahead = i + 1;
                while (ahead < x.length - 1 && x[ahead] == x[i]) ahead++;
                if (x[ahead] < x[i]) {
                    peaks.add((i + ahead - 1) / 2);      // middle of a flat top
                    i = ahead;
                }
            }
            i++;
        }
        if (distance > 1 && peaks.size() > 1) {
            Integer[] order = peaks.toArray(new Integer[0]);
            boolean[] removed = new boolean[order.length];
            Integer[] byHeight = new Integer[order.length];
            for (int j = 0; j < order.length; j++) byHeight[j] = j;
            Arrays.sort(byHeight, (p, q) -> Double.compare(x[order[q]], x[order[p]]));
            for (int j : byHeight) {
                if (removed[j]) continue;
                for (int k = j - 1; k >= 0 && order[j] - order[k] < distance; k--) removed[k] = true;
                for (int k = j + 1; k < order.length && order[k] - order[j] < distance; k++) removed[k] = true;
            }
            List<Integer> kept = new ArrayList<>();
            for (int j = 0; j < order.length; j++) if (!removed[j]) kept.add(order[j]);
            peaks = kept;
        }
        List<Integer> out = new ArrayList<>();
        for (int p : peaks) {
            double leftMin = x[p], rightMin = x[p];
            for (int j = p; j >= 0 && x[j] <= x[p]; j--) leftMin = Math.min(leftMin, x[j]);
            for (int j = p; j < x.length && x[j] <= x[p]; j++) rightMin = Math.min(rightMin, x[j]);
            if (x[p] - Math.max(leftMin, rightMin) >= prominence) out.add(p);
        }
        return out;
    }

    /** Autocorrelation peak at 70-400 Hz pitch lags of one window (voice ~0.6-0.9, airflow noise low). */
    static double pitchStrength(double[] y) {
        double mean = 0;
        for (double v : y) mean += v;
        mean /= y.length;
        double[] f = new double[WIN];
        for (int i = 0; i < WIN; i++) f[i] = (y[i] - mean) * HANN[i];
        double r0 = 0;
        for (double v : f) r0 += v * v;
        if (r0 <= 0) return 0;
        double best = -Double.MAX_VALUE;
        for (int lag = PITCH_LAG_MIN; lag <= PITCH_LAG_MAX; lag++) {
            double r = 0;
            for (int i = 0; i + lag < WIN; i++) r += f[i] * f[i + lag];
            best = Math.max(best, r / r0 / OVERLAP[lag]);
        }
        return best;
    }

    private static double filter(double[][] sos, double[][] z, double x) {
        for (int s = 0; s < sos.length; s++) {
            double[] c = sos[s];
            double y = c[0] * x + z[s][0];
            z[s][0] = c[1] * x - c[3] * y + z[s][1];
            z[s][1] = c[2] * x - c[4] * y;
            x = y;
        }
        return x;
    }

    static double lag1(double[] v) {
        if (v.length < 4) return 0;
        double mean = 0;
        for (double x : v) mean += x;
        mean /= v.length;
        double num = 0, den = 0;
        for (int i = 0; i < v.length; i++) {
            double a = v[i] - mean;
            den += a * a;
            if (i > 0) num += (v[i - 1] - mean) * a;
        }
        return den == 0 ? 0 : num / den;
    }

    static double median(double[] v) {
        return percentile(v, 50);
    }

    /** Linear-interpolated percentile, as numpy.percentile. */
    static double percentile(double[] v, double q) {
        double[] s = v.clone();
        Arrays.sort(s);
        double pos = (s.length - 1) * q / 100.0;
        int lo = (int) Math.floor(pos);
        int hi = Math.min(s.length - 1, lo + 1);
        return s[lo] + (s[hi] - s[lo]) * (pos - lo);
    }

    private static int[] toArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }
}
