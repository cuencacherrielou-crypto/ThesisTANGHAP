package com.sti.tanghap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.sti.tanghap.ai.AiCoughClassifier;
import com.sti.tanghap.ai.CoughClass;
import com.sti.tanghap.ai.processor.MfccExtractor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Random;

/** Runs the real tanghap_model.tflite through the app's classifier on a device. */
@RunWith(AndroidJUnit4.class)
public class CoughModelDeviceTest {

    private AiCoughClassifier classifier;

    @Before
    public void setUp() throws Exception {
        classifier = new AiCoughClassifier(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    @After
    public void tearDown() {
        classifier.close();
    }

    @Test
    public void modelReturnsAProbabilityForEachClass() {
        for (byte[] clip : new byte[][]{silence(), noise(2000, 1), burst(), noise(12000, 2)}) {
            AiCoughClassifier.Result r = classifier.classify(clip);
            Log.i("CoughModelDeviceTest", r.type + " " + r.percent() + "% " + Arrays.toString(r.probabilities));

            assertEquals(CoughClass.values().length, r.probabilities.length);
            float sum = 0;
            for (float p : r.probabilities) {
                assertTrue(p >= 0f && p <= 1f);
                sum += p;
            }
            assertEquals("softmax output sums to 1", 1f, sum, 1e-3f);
            assertEquals(r.probabilities[r.type.ordinal()], r.confidence, 0f);
            for (float p : r.probabilities) assertTrue(r.confidence >= p);
        }
    }

    @Test
    public void sameClipGivesSameResult() {
        byte[] clip = burst();
        assertArrayEquals(classifier.classify(clip).probabilities, classifier.classify(clip).probabilities, 0f);
    }

    @Test
    public void clipIsClassifiedFasterThanTheOneSecondStep() {
        byte[] clip = burst();
        float[] samples = new float[MfccExtractor.CLIP_SAMPLES];
        MfccExtractor features = new MfccExtractor();
        classifier.classify(clip); // warm-up

        long t0 = System.nanoTime();
        for (int i = 0; i < 5; i++) features.extract(samples);
        long featureMs = (System.nanoTime() - t0) / 5_000_000;

        t0 = System.nanoTime();
        for (int i = 0; i < 5; i++) classifier.classify(clip);
        long totalMs = (System.nanoTime() - t0) / 5_000_000;

        Log.i("CoughModelDeviceTest", "features " + featureMs + " ms, features + model " + totalMs + " ms per clip");
        assertTrue("classification must keep up with the 1 s window step: " + totalMs + " ms", totalMs < 1000);
    }

    private static byte[] silence() {
        return new byte[MfccExtractor.CLIP_SAMPLES * 2];
    }

    private static byte[] noise(int amplitude, long seed) {
        Random r = new Random(seed);
        short[] s = new short[MfccExtractor.CLIP_SAMPLES];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((r.nextFloat() * 2 - 1) * amplitude);
        return toBytes(s);
    }

    /** A loud, short, noisy burst roughly shaped like a cough. */
    private static byte[] burst() {
        Random r = new Random(3);
        short[] s = new short[MfccExtractor.CLIP_SAMPLES];
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / MfccExtractor.SAMPLE_RATE;
            double envelope = Math.exp(-Math.pow((t - 0.8) / 0.08, 2));
            s[i] = (short) (envelope * 20000 * (r.nextFloat() * 2 - 1) + 300 * (r.nextFloat() * 2 - 1));
        }
        return toBytes(s);
    }

    private static byte[] toBytes(short[] s) {
        byte[] b = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            b[2 * i] = (byte) (s[i] & 0xFF);
            b[2 * i + 1] = (byte) ((s[i] >> 8) & 0xFF);
        }
        return b;
    }
}
