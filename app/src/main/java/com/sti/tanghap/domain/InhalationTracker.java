package com.sti.tanghap.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Measures inhalations from the BLE pressure stream.
 *
 * Inhaling sucks air through the mouthpiece, which the device reports as negative kPa
 * (suction); exhaling or coughing into it reads positive and is not an inhalation. An
 * inhalation is a run of samples with suction above {@link #ONSET_FRACTION} of the threshold
 * whose strongest suction reaches the threshold, so its duration covers the whole breath in,
 * not only its strongest part. Packets carry no per-sample timestamps, so the sample period
 * is estimated from packet arrival times and the number of samples received, and each
 * sample's time is estimated back from its packet's arrival time.
 *
 * A run that only repeats one value, or reaches the sensor's limit, is a sensor glitch, not
 * a breath: a real breath rises and falls, and an HX710B that loses its connection reports
 * a frozen or full-scale number (seen on the TANGHAP device as flat -7.81 and -10.42 kPa).
 * Glitches are counted separately and never become inhalations.
 */
public class InhalationTracker {

    /** One measured inhalation. */
    public static final class Inhalation {
        /** Estimated wall-clock start time, epoch milliseconds. */
        public final long startMillis;
        public final long durationMillis;
        public final float peakKpa;

        Inhalation(long startMillis, long durationMillis, float peakKpa) {
            this.startMillis = startMillis;
            this.durationMillis = durationMillis;
            this.peakKpa = peakKpa;
        }
    }

    /** Memory cap on the stored per-inhalation list (several hours of breathing). */
    private static final int MAX_STORED_INHALATIONS = 5000;

    public static final float DEFAULT_THRESHOLD_KPA = 0.3f;
    /** An inhalation starts and ends where suction crosses this fraction of the threshold. */
    public static final float ONSET_FRACTION = 0.25f;
    /** Runs shorter than this are treated as noise, not an inhalation. */
    private static final int MIN_SAMPLES_PER_INHALATION = 2;
    /**
     * The HX710B's full scale is about 10.42 kPa (8388607 counts at 805306 counts per kPa);
     * a reading this close to it is the sensor's limit, not a measurement.
     */
    public static final float SENSOR_LIMIT_KPA = 10.3f;

    private final float thresholdKpa;

    private long firstArrivalMillis = -1;
    private long lastArrivalMillis = -1;
    private long samplesBeforeLastPacket = 0;
    private long totalSamples = 0;

    private int currentRunSamples = 0;
    private int inhalationCount = 0;
    private long totalInhaleSamples = 0;
    private int longestInhaleSamples = 0;
    private float peakEffortKpa = 0f;
    private long currentRunStartMillis = 0;
    private float currentRunPeakKpa = 0f;
    private float currentRunFirstKpa = 0f;
    private boolean currentRunVaries = false;
    private int sensorGlitchCount = 0;
    private final List<Inhalation> inhalations = new ArrayList<>();

    public InhalationTracker() {
        this(DEFAULT_THRESHOLD_KPA);
    }

    public InhalationTracker(float thresholdKpa) {
        this.thresholdKpa = thresholdKpa;
    }

    public void addSamples(float[] kpaSamples, long arrivalMillis) {
        if (kpaSamples == null || kpaSamples.length == 0) return;
        // Sample period known so far, used to date the samples inside this packet
        float msPerSampleSoFar = msPerSample();
        if (firstArrivalMillis < 0) firstArrivalMillis = arrivalMillis;
        samplesBeforeLastPacket = totalSamples;
        lastArrivalMillis = arrivalMillis;

        int n = kpaSamples.length;
        float onsetKpa = thresholdKpa * ONSET_FRACTION;
        for (int i = 0; i < n; i++) {
            float suction = -kpaSamples[i];
            if (suction >= onsetKpa) {
                if (currentRunSamples == 0) {
                    // The last sample of a packet arrives with it; earlier ones are one period apart
                    currentRunStartMillis = arrivalMillis - Math.round((n - 1 - i) * msPerSampleSoFar);
                    currentRunPeakKpa = 0f;
                    currentRunFirstKpa = suction;
                    currentRunVaries = false;
                } else if (suction != currentRunFirstKpa) {
                    currentRunVaries = true;
                }
                if (suction > currentRunPeakKpa) currentRunPeakKpa = suction;
                currentRunSamples++;
            } else {
                closeRun();
            }
            totalSamples++;
        }
    }

    /** Ends an inhalation still in progress, for example when the session stops. */
    public void finish() {
        closeRun();
    }

    private void closeRun() {
        if (currentRunSamples >= MIN_SAMPLES_PER_INHALATION && currentRunPeakKpa >= thresholdKpa) {
            if (isSensorGlitch(currentRunVaries, currentRunPeakKpa)) {
                sensorGlitchCount++;
                currentRunSamples = 0;
                return;
            }
            inhalationCount++;
            if (currentRunPeakKpa > peakEffortKpa) peakEffortKpa = currentRunPeakKpa;
            totalInhaleSamples += currentRunSamples;
            if (currentRunSamples > longestInhaleSamples) longestInhaleSamples = currentRunSamples;
            if (inhalations.size() < MAX_STORED_INHALATIONS) {
                inhalations.add(new Inhalation(currentRunStartMillis,
                        Math.round(currentRunSamples * msPerSample()), currentRunPeakKpa));
            }
        }
        currentRunSamples = 0;
    }

    /**
     * True for a suction run that cannot be a breath: one repeated value, or the sensor's limit.
     *
     * @param varies whether the run held more than one value
     */
    public static boolean isSensorGlitch(boolean varies, float peakSuctionKpa) {
        return !varies || peakSuctionKpa >= SENSOR_LIMIT_KPA;
    }

    /**
     * True while suction is above the start level and looks like a breath. A run that so far
     * only repeats one value or sits at the sensor's limit is not shown: the faulty HX710B sent
     * flat jumps of exactly 2.60 kPa that the live screen showed as "Inhaling" before the run
     * ended and was rejected as a glitch.
     */
    public boolean isInhaling() {
        return currentRunSamples > 0 && !isSensorGlitch(currentRunVaries, currentRunPeakKpa);
    }

    /** Length of the suction run in progress so far; 0 when not inhaling. */
    public long getCurrentInhaleMillis() {
        return isInhaling() ? Math.round(currentRunSamples * msPerSample()) : 0;
    }

    /** Strongest suction of the run in progress; 0 when not inhaling. */
    public float getCurrentInhalePeakKpa() {
        return isInhaling() ? currentRunPeakKpa : 0f;
    }

    /** The most recent completed inhalation, or null when there is none yet. */
    public Inhalation getLastInhalation() {
        return inhalations.isEmpty() ? null : inhalations.get(inhalations.size() - 1);
    }

    /** Suction runs rejected as sensor glitches (frozen value or sensor limit). */
    public int getSensorGlitchCount() {
        return sensorGlitchCount;
    }

    /** Estimated milliseconds per sample; 0 until at least two packets have arrived. */
    public float msPerSample() {
        if (samplesBeforeLastPacket <= 0 || lastArrivalMillis <= firstArrivalMillis) return 0f;
        return (lastArrivalMillis - firstArrivalMillis) / (float) samplesBeforeLastPacket;
    }

    public int getInhalationCount() {
        return inhalationCount;
    }

    public long getAverageInhaleMillis() {
        if (inhalationCount == 0) return 0;
        return Math.round((totalInhaleSamples / (float) inhalationCount) * msPerSample());
    }

    public long getLongestInhaleMillis() {
        return Math.round(longestInhaleSamples * msPerSample());
    }

    /** Strongest suction of any completed inhalation, kPa (positive); 0 when none yet. */
    public float getPeakEffortKpa() {
        return peakEffortKpa;
    }

    /** Every completed inhalation with its start time, oldest first. */
    public List<Inhalation> getInhalations() {
        return Collections.unmodifiableList(inhalations);
    }
}
