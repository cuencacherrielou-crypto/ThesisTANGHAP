package com.sti.tanghap.ble.ble;

/**
 * Decodes the device's compressed audio packets (characteristic AB04) into 16-bit little-endian
 * PCM, the format the cough pipeline reads. Raw PCM at 32 KB/s was close to the BLE link's
 * capacity and the device threw packets away when the phone's radio was busy. The compression
 * is lossless (first-order prediction + Rice codes), so the model hears exactly the same samples.
 *
 * Packet: uint32 stream position of the first sample, uint16 sample count n, uint8 Rice
 * parameter k, int16 first sample, then n-1 zigzag-mapped differences, each as q = u >> k ones,
 * a zero and the low k bits (MSB first); q >= ESC is ESC ones and the 17-bit value.
 *
 * The stream position shows exactly how many samples a lost packet held. A gap of up to a
 * second is filled with the quietest recent audio, so timing is kept and the cough detector's
 * noise floor is not pulled down by digital silence.
 */
public final class AudioPacketDecoder {

    public static final int HEADER_BYTES = 9;
    static final int ESC = 16;

    /** Gaps longer than this (1 s) are a restart, not lost packets, and are not filled. */
    static final int MAX_FILL_SAMPLES = 16000;

    /** Recent packets kept to choose the background filler from. */
    private static final int RECENT_PACKETS = 40;
    private static final int MIN_FILLER_SAMPLES = 64;

    private long expectedPosition = -1;
    private long receivedSamples = 0;
    private long lostSamples = 0;
    private int lastGapSamples = 0;
    private final byte[][] recent = new byte[RECENT_PACKETS][];
    private final double[] recentEnergy = new double[RECENT_PACKETS];
    private int recentCount = 0;
    private int recentNext = 0;

    /** Call on every new connection. */
    public synchronized void reset() {
        expectedPosition = -1;
        recentCount = 0;
        recentNext = 0;
    }

    public synchronized long getReceivedSamples() { return receivedSamples; }

    public synchronized long getLostSamples() { return lostSamples; }

    /** Samples lost (and filled) just before the last decoded packet, 0 when none. */
    public synchronized int getLastGapSamples() { return lastGapSamples; }

    /**
     * @return PCM for this packet, preceded by filler for audio lost just before it, or null
     *         for a malformed packet
     */
    public synchronized byte[] decode(byte[] p) {
        if (p == null || p.length < HEADER_BYTES) return null;
        long position = (p[0] & 0xFFL) | (p[1] & 0xFFL) << 8 | (p[2] & 0xFFL) << 16 | (p[3] & 0xFFL) << 24;
        int count = (p[4] & 0xFF) | (p[5] & 0xFF) << 8;
        int k = p[6] & 0xFF;
        if (count == 0 || k > 14) return null;

        byte[] pcm = new byte[count * 2];
        int prev = (short) ((p[7] & 0xFF) | (p[8] & 0xFF) << 8);
        pcm[0] = (byte) prev;
        pcm[1] = (byte) (prev >> 8);
        BitReader in = new BitReader(p, HEADER_BYTES * 8);
        try {
            for (int i = 1; i < count; i++) {
                int q = 0;
                while (q < ESC && in.bit() == 1) q++;
                int u = q >= ESC ? in.bits(17) : (q << k) | in.bits(k);
                int r = (u & 1) == 0 ? u >>> 1 : -((u + 1) >>> 1);
                prev += r;
                if (prev < -32768 || prev > 32767) return null;
                pcm[2 * i] = (byte) prev;
                pcm[2 * i + 1] = (byte) (prev >> 8);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            return null;                    // ran past the end: corrupt packet
        }
        // The data must end in the last byte (only zero padding after it)
        if ((in.position() + 7) / 8 != p.length) return null;

        long gap = expectedPosition < 0 ? 0 : (position - expectedPosition) & 0xFFFFFFFFL;
        expectedPosition = (position + count) & 0xFFFFFFFFL;
        receivedSamples += count;
        lastGapSamples = 0;
        byte[] out = pcm;
        if (gap > 0 && gap <= MAX_FILL_SAMPLES) {
            lostSamples += gap;
            lastGapSamples = (int) gap;
            byte[] filler = quietestRecent();
            int gapBytes = (int) gap * 2;
            out = new byte[gapBytes + pcm.length];
            if (filler != null) {
                for (int off = 0; off < gapBytes; off += filler.length) {
                    System.arraycopy(filler, 0, out, off, Math.min(filler.length, gapBytes - off));
                }
            }
            System.arraycopy(pcm, 0, out, gapBytes, pcm.length);
        }
        remember(pcm);
        return out;
    }

    private void remember(byte[] pcm) {
        if (pcm.length < MIN_FILLER_SAMPLES * 2) return;   // too short to repeat as background
        double energy = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            energy += (double) s * s;
        }
        recent[recentNext] = pcm;
        recentEnergy[recentNext] = energy / Math.max(1, pcm.length / 2);
        recentNext = (recentNext + 1) % RECENT_PACKETS;
        if (recentCount < RECENT_PACKETS) recentCount++;
    }

    /** Background sound for a gap: the quietest recent packet (null = silence). */
    private byte[] quietestRecent() {
        int best = -1;
        for (int i = 0; i < recentCount; i++) {
            if (best < 0 || recentEnergy[i] < recentEnergy[best]) best = i;
        }
        return best < 0 ? null : recent[best];
    }

    private static final class BitReader {
        private final byte[] data;
        private int pos;

        BitReader(byte[] data, int startBit) {
            this.data = data;
            this.pos = startBit;
        }

        int bit() {
            int b = (data[pos >> 3] >> (7 - (pos & 7))) & 1;
            pos++;
            return b;
        }

        int bits(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) v = (v << 1) | bit();
            return v;
        }

        int position() { return pos; }
    }
}
