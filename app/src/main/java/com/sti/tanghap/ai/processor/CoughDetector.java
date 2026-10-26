package com.sti.tanghap.ai.processor;

/**
 * Finds coughs in the microphone stream by their sound: a cough is a sudden burst well above
 * the background noise that lasts like a cough (0.2 to 3 seconds) and then goes quiet again.
 * Every such burst is reported as a cough; the cough model only decides its type.
 *
 * Feed 16 kHz 16-bit little-endian mono PCM in any chunk sizes. Not thread-safe.
 */
public final class CoughDetector {

    public static final int FRAME_SAMPLES = 800;          // 50 ms at 16 kHz
    /** A burst starts this far above the background noise. */
    public static final float ONSET_DB = 15f;
    /** The burst lasts while the sound stays this far above the background noise. */
    public static final float RELEASE_DB = 8f;
    /** Quiet frames in a row that end a burst (100 ms). */
    public static final int RELEASE_FRAMES = 2;
    /** Shortest burst counted as a cough (200 ms); shorter clicks and taps are ignored. */
    public static final int MIN_FRAMES = 4;
    /** Longest burst counted as a cough (3 s); longer sound is speech or ongoing noise. */
    public static final int MAX_FRAMES = 60;
    /** Bursts quieter than this (dBFS) are ignored, whatever the background level. */
    public static final float MIN_LEVEL_DB = -50f;
    /** Level reported for digital silence. */
    public static final float SILENCE_DB = -120f;

    // Background noise follows quieter sound quickly and louder sound slowly (~2.5 s)
    private static final float FLOOR_DOWN = 0.3f;
    private static final float FLOOR_UP = 0.02f;

    /** One cough heard in the stream; sample numbers count from the first sample fed in. */
    public static final class Cough {
        public final long startSample;
        public final long endSample;      // exclusive
        public final long peakSample;     // middle of the loudest 50 ms
        public final float peakDb;
        public final float backgroundDb;

        public Cough(long startSample, long endSample, long peakSample, float peakDb, float backgroundDb) {
            this.startSample = startSample;
            this.endSample = endSample;
            this.peakSample = peakSample;
            this.peakDb = peakDb;
            this.backgroundDb = backgroundDb;
        }
    }

    public interface Callback {
        /** Loudness of each 50 ms frame in dBFS, in stream order. */
        void onFrameLevel(float levelDb);

        void onCough(Cough cough);
    }

    // Partial frame carried between chunks
    private final double[] frame = new double[FRAME_SAMPLES];
    private int frameFill = 0;
    private int carryByte = -1;
    private long frameIndex = 0;

    private boolean hasFloor = false;
    private float floorDb;

    // Burst in progress (startFrame < 0 = none)
    private long startFrame = -1;
    private long lastLoudFrame;
    private long peakFrame;
    private float peakDb;
    private float burstFloorDb;
    private int quietFrames;

    public void process(byte[] pcm16, Callback cb) {
        int i = 0;
        if (carryByte >= 0 && pcm16.length > 0) {
            addSample((short) ((pcm16[0] << 8) | carryByte), cb);
            carryByte = -1;
            i = 1;
        }
        for (; i + 1 < pcm16.length; i += 2) {
            addSample((short) ((pcm16[i + 1] << 8) | (pcm16[i] & 0xFF)), cb);
        }
        if (i < pcm16.length) carryByte = pcm16[i] & 0xFF;
    }

    /** Ends a burst still in progress (e.g. when the session ends). */
    public void flush(Callback cb) {
        if (startFrame >= 0) finishBurst(cb);
    }

    private void addSample(short s, Callback cb) {
        frame[frameFill++] = s / 32768.0;
        if (frameFill == FRAME_SAMPLES) {
            frameFill = 0;
            onFrame(levelDb(frame), cb);
            frameIndex++;
        }
    }

    /** RMS level of the frame in dBFS, ignoring any DC offset of the microphone. */
    static float levelDb(double[] f) {
        double mean = 0;
        for (double v : f) mean += v;
        mean /= f.length;
        double sq = 0;
        for (double v : f) sq += (v - mean) * (v - mean);
        sq /= f.length;
        return sq > 0 ? (float) Math.max(SILENCE_DB, 10 * Math.log10(sq)) : SILENCE_DB;
    }

    private void onFrame(float level, Callback cb) {
        cb.onFrameLevel(level);
        if (!hasFloor) {
            hasFloor = true;
            floorDb = level;
        }

        if (startFrame < 0) {
            if (level >= floorDb + ONSET_DB && level >= MIN_LEVEL_DB) {
                startFrame = frameIndex;
                lastLoudFrame = frameIndex;
                peakFrame = frameIndex;
                peakDb = level;
                burstFloorDb = floorDb;
                quietFrames = 0;
            } else {
                floorDb += (level - floorDb) * (level < floorDb ? FLOOR_DOWN : FLOOR_UP);
            }
            return;
        }

        if (level >= burstFloorDb + RELEASE_DB) {
            lastLoudFrame = frameIndex;
            quietFrames = 0;
            if (level > peakDb) {
                peakDb = level;
                peakFrame = frameIndex;
            }
        } else if (++quietFrames >= RELEASE_FRAMES) {
            finishBurst(cb);
        }
    }

    private void finishBurst(Callback cb) {
        long frames = lastLoudFrame - startFrame + 1;
        if (frames >= MIN_FRAMES && frames <= MAX_FRAMES) {
            cb.onCough(new Cough(startFrame * FRAME_SAMPLES, (lastLoudFrame + 1) * FRAME_SAMPLES,
                    peakFrame * FRAME_SAMPLES + FRAME_SAMPLES / 2, peakDb, burstFloorDb));
        }
        startFrame = -1;
    }
}
