package com.sti.tanghap.ai.processor;

/**
 * Loudness of an audio clip, used to tell the user's cough (closest to the device, loudest)
 * from coughs of people farther away. It only measures the clip; the audio given to the
 * cough model is never changed.
 */
public final class AudioLevel {

    /** Quietest level reported, for digital silence. */
    public static final float SILENCE_DBFS = -120f;

    private static final int FRAME_SAMPLES = 800; // 50 ms at 16 kHz, about the length of a cough burst
    private static final int HOP_SAMPLES = 400;

    private AudioLevel() {}

    /**
     * Loudest 50 ms of the clip as RMS level in dB relative to full scale (0 dBFS = maximum).
     * Using the loudest short window measures the cough burst itself rather than the
     * quiet parts of the 2-second clip around it.
     *
     * @param pcm16 16-bit little-endian mono PCM
     */
    public static float peakDbfs(byte[] pcm16) {
        if (pcm16 == null) return SILENCE_DBFS;
        int n = pcm16.length / 2;
        if (n == 0) return SILENCE_DBFS;
        int frame = Math.min(FRAME_SAMPLES, n);

        double maxMeanSquare = 0;
        for (int start = 0; start + frame <= n; start += HOP_SAMPLES) {
            double sum = 0;
            for (int i = start; i < start + frame; i++) {
                short s = (short) ((pcm16[2 * i + 1] << 8) | (pcm16[2 * i] & 0xFF));
                double v = s / 32768.0;
                sum += v * v;
            }
            maxMeanSquare = Math.max(maxMeanSquare, sum / frame);
            if (start + frame == n) break;
        }
        if (maxMeanSquare <= 0) return SILENCE_DBFS;
        return (float) Math.max(SILENCE_DBFS, 10 * Math.log10(maxMeanSquare));
    }
}
