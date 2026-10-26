package com.sti.tanghap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.sti.tanghap.ai.processor.CoughDetector;
import com.sti.tanghap.ai.processor.PcmRingBuffer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class CoughDetectorTest {

    private static final int SR = 16000;

    /** Builds 16 kHz PCM: quiet background noise with louder noise bursts. */
    private static final class Audio {
        final short[] s;
        final Random r = new Random(7);

        Audio(double seconds, int backgroundAmplitude) {
            s = new short[(int) (seconds * SR)];
            for (int i = 0; i < s.length; i++) s[i] = (short) noise(backgroundAmplitude);
        }

        Audio burst(double fromSec, double lengthSec, int amplitude) {
            int from = (int) (fromSec * SR), to = Math.min(s.length, from + (int) (lengthSec * SR));
            for (int i = from; i < to; i++) s[i] = (short) noise(amplitude);
            return this;
        }

        int noise(int amplitude) {
            return (int) ((r.nextDouble() * 2 - 1) * amplitude);
        }

        byte[] bytes() {
            byte[] b = new byte[s.length * 2];
            for (int i = 0; i < s.length; i++) {
                b[2 * i] = (byte) (s[i] & 0xFF);
                b[2 * i + 1] = (byte) ((s[i] >> 8) & 0xFF);
            }
            return b;
        }
    }

    private static final class Collector implements CoughDetector.Callback {
        final List<CoughDetector.Cough> coughs = new ArrayList<>();
        final List<Float> levels = new ArrayList<>();

        @Override
        public void onFrameLevel(float levelDb) {
            levels.add(levelDb);
        }

        @Override
        public void onCough(CoughDetector.Cough cough) {
            coughs.add(cough);
        }
    }

    private static Collector run(byte[] pcm, int chunkBytes) {
        CoughDetector d = new CoughDetector();
        Collector c = new Collector();
        for (int i = 0; i < pcm.length; i += chunkBytes) {
            d.process(Arrays.copyOfRange(pcm, i, Math.min(pcm.length, i + chunkBytes)), c);
        }
        return c;
    }

    // Background about -50 dBFS (amplitude 180), a cough about 30 dB louder (amplitude 6000)

    @Test
    public void coughBurst_isSensedOnce_atTheRightTime() {
        Collector c = run(new Audio(4, 180).burst(1.5, 0.4, 6000).bytes(), 244);
        assertEquals(1, c.coughs.size());
        CoughDetector.Cough k = c.coughs.get(0);
        assertEquals(1.5 * SR, k.startSample, CoughDetector.FRAME_SAMPLES);
        assertEquals(1.9 * SR, k.endSample, CoughDetector.FRAME_SAMPLES);
        assertTrue(k.peakSample >= k.startSample && k.peakSample < k.endSample);
        assertTrue(k.peakDb - k.backgroundDb > 25);
    }

    @Test
    public void everyCoughInABout_isSensed() {
        Audio a = new Audio(6, 180).burst(1.0, 0.3, 6000).burst(1.8, 0.4, 8000).burst(3.0, 0.5, 5000);
        assertEquals(3, run(a.bytes(), 244).coughs.size());
    }

    @Test
    public void chunkSizeDoesNotChangeTheResult() {
        byte[] pcm = new Audio(5, 180).burst(1.0, 0.3, 6000).burst(2.5, 0.6, 9000).bytes();
        Collector whole = run(pcm, pcm.length);
        Collector odd = run(pcm, 243); // odd sizes split samples across chunks
        assertEquals(whole.levels, odd.levels);
        assertEquals(whole.coughs.size(), odd.coughs.size());
        for (int i = 0; i < whole.coughs.size(); i++) {
            assertEquals(whole.coughs.get(i).peakSample, odd.coughs.get(i).peakSample);
        }
    }

    @Test
    public void shortClickAndLongNoise_areNotCoughs() {
        Audio a = new Audio(10, 180)
                .burst(1.0, 0.1, 8000)   // 100 ms tap on the device
                .burst(3.0, 4.0, 6000);  // 4 s of talking / ongoing noise
        assertEquals(0, run(a.bytes(), 244).coughs.size());
    }

    @Test
    public void silenceAndFaintSounds_areNotCoughs() {
        assertEquals(0, run(new byte[SR * 2 * 3], 244).coughs.size());
        // 20 dB above a very quiet background, but still below -50 dBFS
        assertEquals(0, run(new Audio(4, 10).burst(1.5, 0.4, 100).bytes(), 244).coughs.size());
    }

    @Test
    public void flush_endsACoughStillInProgress() {
        CoughDetector d = new CoughDetector();
        Collector c = new Collector();
        d.process(new Audio(2, 180).burst(1.6, 0.4, 6000).bytes(), c);
        assertEquals(0, c.coughs.size());
        d.flush(c);
        assertEquals(1, c.coughs.size());
    }

    @Test
    public void frameLevel_isRmsInDbfs() {
        Collector c = run(new Audio(1, 0).burst(0, 1, 0).bytes(), 244);
        assertEquals(CoughDetector.SILENCE_DB, c.levels.get(0), 0f);
        short[] square = new short[SR];
        for (int i = 0; i < square.length; i++) square[i] = (short) (i % 2 == 0 ? 16384 : -16384);
        Audio a = new Audio(1, 0);
        System.arraycopy(square, 0, a.s, 0, square.length);
        assertEquals(-6.02f, run(a.bytes(), 244).levels.get(3), 0.05f);
    }

    // ---------- Recent-audio buffer ----------

    @Test
    public void ringBuffer_returnsTheRequestedSamples_paddedWithSilence() {
        PcmRingBuffer rb = new PcmRingBuffer(10);
        byte[] in = new byte[30]; // samples 1..15
        for (int i = 0; i < 15; i++) in[2 * i] = (byte) (i + 1);
        rb.write(Arrays.copyOfRange(in, 0, 7)); // odd split
        rb.write(Arrays.copyOfRange(in, 7, 30));
        assertEquals(15, rb.totalSamples());

        byte[] clip = rb.clip(3, 14); // samples 3..16: 3, 4 overwritten, 15, 16 not written yet
        byte[] want = new byte[28];
        for (int n = 5; n <= 14; n++) want[2 * (n - 3)] = (byte) (n + 1);
        assertArrayEquals(want, clip);
        assertArrayEquals(new byte[8], rb.clip(-4, 4));
    }
}
