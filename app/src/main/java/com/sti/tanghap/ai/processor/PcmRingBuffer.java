package com.sti.tanghap.ai.processor;

/**
 * The most recent stretch of a 16-bit PCM stream, so a 2-second clip around a cough can be
 * cut out after the cough has ended. Not thread-safe.
 */
public final class PcmRingBuffer {

    private final short[] buf;
    private long total = 0;     // samples written since the start
    private int carryByte = -1;

    public PcmRingBuffer(int capacitySamples) {
        buf = new short[capacitySamples];
    }

    /** @param pcm16 16-bit little-endian PCM in any chunk size */
    public void write(byte[] pcm16) {
        int i = 0;
        if (carryByte >= 0 && pcm16.length > 0) {
            put((short) ((pcm16[0] << 8) | carryByte));
            carryByte = -1;
            i = 1;
        }
        for (; i + 1 < pcm16.length; i += 2) put((short) ((pcm16[i + 1] << 8) | (pcm16[i] & 0xFF)));
        if (i < pcm16.length) carryByte = pcm16[i] & 0xFF;
    }

    private void put(short s) {
        buf[(int) (total % buf.length)] = s;
        total++;
    }

    /** Samples written so far. */
    public long totalSamples() {
        return total;
    }

    /**
     * Copies samples [startSample, startSample + length) as 16-bit little-endian PCM; samples
     * not written yet, or already overwritten, are silence.
     */
    public byte[] clip(long startSample, int length) {
        byte[] out = new byte[length * 2];
        long oldest = Math.max(0, total - buf.length);
        for (int i = 0; i < length; i++) {
            long n = startSample + i;
            if (n < oldest || n >= total) continue;
            short s = buf[(int) (n % buf.length)];
            out[2 * i] = (byte) (s & 0xFF);
            out[2 * i + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return out;
    }
}
