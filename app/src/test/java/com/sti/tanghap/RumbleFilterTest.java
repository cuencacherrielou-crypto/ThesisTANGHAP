package com.sti.tanghap;

import static org.junit.Assert.assertEquals;

import com.sti.tanghap.ai.processor.RumbleFilter;

import org.junit.Test;

public class RumbleFilterTest {

    /** 0.3 offset + 20 Hz rumble + 1 kHz tone, as float32 like the app's samples. */
    private static float[] signal() {
        float[] x = new float[4000];
        for (int n = 0; n < x.length; n++) {
            x[n] = (float) (0.3 + 0.2 * Math.sin(2 * Math.PI * 20 * n / 16000.0)
                    + 0.1 * Math.sin(2 * Math.PI * 1000 * n / 16000.0));
        }
        return x;
    }

    @Test
    public void matchesScipySosfilt() {
        // y = sosfilt(butter(4, 100, 'highpass', fs=16000, output='sos'), x - x.mean())
        float[] y = RumbleFilter.apply(signal());
        assertEquals(1.1476755840752498e-08, y[0], 1e-6);
        assertEquals(0.03784644731863004, y[1], 1e-6);
        assertEquals(0.0662746045781152, y[2], 1e-6);
        assertEquals(-0.09174572024997382, y[10], 1e-6);
        assertEquals(0.09290681351409043, y[100], 1e-6);
        assertEquals(-0.02526977329723246, y[1000], 1e-6);
        assertEquals(-0.013559328347997404, y[3999], 1e-6);
    }

    @Test
    public void removesOffsetAndRumble_keepsTheTone() {
        float[] y = RumbleFilter.apply(signal());
        double sq = 0;
        for (int i = 2000; i < y.length; i++) sq += y[i] * y[i];
        // only the 1 kHz tone (RMS 0.1 / sqrt 2) is left
        assertEquals(0.1 / Math.sqrt(2), Math.sqrt(sq / 2000), 0.002);
    }
}
