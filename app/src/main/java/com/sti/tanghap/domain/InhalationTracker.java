package com.sti.tanghap.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Measures inhalations from the BLE pressure stream.
 *
 * An inhalation is a run of consecutive samples whose absolute pressure is at or
 * above the threshold. Packets carry no per-sample timestamps, so the sample period
 * is estimated from packet arrival times and the number of samples received, and each
 * sample's time is estimated back from its packet's arrival time.
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

    public static final float DEFAULT_THRESHOLD_KPA = 1.5f;
    /** Runs shorter than this are treated as noise, not an inhalation. */
    private static final int MIN_SAMPLES_PER_INHALATION = 2;

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
        for (int i = 0; i < n; i++) {
            float abs = Math.abs(kpaSamples[i]);
            if (abs > peakEffortKpa) peakEffortKpa = abs;
            if (abs >= thresholdKpa) {
                if (currentRunSamples == 0) {
                    // The last sample of a packet arrives with it; earlier ones are one period apart
                    currentRunStartMillis = arrivalMillis - Math.round((n - 1 - i) * msPerSampleSoFar);
                    currentRunPeakKpa = 0f;
                }
                if (abs > currentRunPeakKpa) currentRunPeakKpa = abs;
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
        if (currentRunSamples >= MIN_SAMPLES_PER_INHALATION) {
            inhalationCount++;
            totalInhaleSamples += currentRunSamples;
            if (currentRunSamples > longestInhaleSamples) longestInhaleSamples = currentRunSamples;
            if (inhalations.size() < MAX_STORED_INHALATIONS) {
                inhalations.add(new Inhalation(currentRunStartMillis,
                        Math.round(currentRunSamples * msPerSample()), currentRunPeakKpa));
            }
        }
        currentRunSamples = 0;
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

    public float getPeakEffortKpa() {
        return peakEffortKpa;
    }

    /** Every completed inhalation with its start time, oldest first. */
    public List<Inhalation> getInhalations() {
        return Collections.unmodifiableList(inhalations);
    }
}
