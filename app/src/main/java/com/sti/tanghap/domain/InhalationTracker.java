package com.sti.tanghap.domain;

/**
 * Measures inhalations from the BLE pressure stream.
 *
 * An inhalation is a run of consecutive samples whose absolute pressure is at or
 * above the threshold. Packets carry no per-sample timestamps, so the sample period
 * is estimated from packet arrival times and the number of samples received.
 */
public class InhalationTracker {

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

    public InhalationTracker() {
        this(DEFAULT_THRESHOLD_KPA);
    }

    public InhalationTracker(float thresholdKpa) {
        this.thresholdKpa = thresholdKpa;
    }

    public void addSamples(float[] kpaSamples, long arrivalMillis) {
        if (kpaSamples == null || kpaSamples.length == 0) return;
        if (firstArrivalMillis < 0) firstArrivalMillis = arrivalMillis;
        samplesBeforeLastPacket = totalSamples;
        lastArrivalMillis = arrivalMillis;

        for (float v : kpaSamples) {
            float abs = Math.abs(v);
            if (abs > peakEffortKpa) peakEffortKpa = abs;
            if (abs >= thresholdKpa) {
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
}
