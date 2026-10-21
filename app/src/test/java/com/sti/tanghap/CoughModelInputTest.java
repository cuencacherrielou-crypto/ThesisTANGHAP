package com.sti.tanghap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sti.tanghap.ai.AiCoughClassifier;
import com.sti.tanghap.ai.CoughClass;
import com.sti.tanghap.ai.processor.MfccExtractor;

import org.junit.Test;

public class CoughModelInputTest {

    @Test
    public void classOrderMatchesModelOutput() {
        // The model's 6 outputs follow the alphabetical label order used in training
        String[] trainingLabels = {"dry_cough", "non_cough", "persistent_cough", "severe_cough", "wet_cough", "wheezing"};
        CoughClass[] classes = CoughClass.values();
        assertEquals(trainingLabels.length, classes.length);
        for (int i = 0; i < classes.length; i++) {
            assertEquals(trainingLabels[i], classes[i].name().toLowerCase());
        }
    }

    @Test
    public void onlyTheFourCoughClassesCountAsCoughs() {
        assertTrue(CoughClass.DRY_COUGH.isCough);
        assertTrue(CoughClass.WET_COUGH.isCough);
        assertTrue(CoughClass.PERSISTENT_COUGH.isCough);
        assertTrue(CoughClass.SEVERE_COUGH.isCough);
        assertFalse(CoughClass.WHEEZING.isCough);
        assertFalse(CoughClass.NON_COUGH.isCough);
        assertEquals("Wheezing Detected (91%)", CoughClass.WHEEZING.displayText(91));
        assertEquals("Cough Detected: Wet cough (87%)", CoughClass.WET_COUGH.displayText(87));
        assertEquals("Dry cough (64% confidence)", CoughClass.DRY_COUGH.findingText(64));
    }

    @Test
    public void predictionsBelowSixtyPercentAreNotTrusted() {
        assertEquals(0.60f, AiCoughClassifier.MIN_CONFIDENCE, 0f);
        assertTrue(result(CoughClass.WET_COUGH, 0.60f).isConfident());
        assertTrue(result(CoughClass.WET_COUGH, 0.95f).isConfident());
        assertFalse(result(CoughClass.WET_COUGH, 0.59f).isConfident());
        assertEquals(87, result(CoughClass.WET_COUGH, 0.871f).percent());
    }

    private static AiCoughClassifier.Result result(CoughClass type, float confidence) {
        return new AiCoughClassifier.Result(type, confidence, new float[CoughClass.values().length]);
    }

    @Test
    public void featuresHaveModelInputShape() {
        float[][][] x = new MfccExtractor().extract(tone(440f, MfccExtractor.CLIP_SAMPLES));
        assertEquals(20, x.length);
        assertEquals(63, x[0].length);
        assertEquals(3, x[0][0].length);
    }

    @Test
    public void shortClipsAreZeroPadded() {
        float[][][] x = new MfccExtractor().extract(tone(440f, 8000));
        assertEquals(63, x[0].length);
        for (float[][] row : x) {
            for (float[] frame : row) {
                for (float v : frame) assertFalse(Float.isNaN(v) || Float.isInfinite(v));
            }
        }
    }

    @Test
    public void silenceGivesFlatMfccAndZeroDeltas() {
        float[][][] x = new MfccExtractor().extract(new float[MfccExtractor.CLIP_SAMPLES]);
        // All mel bands sit at the 1e-10 floor (-100 dB), so only c0 is non-zero
        assertEquals(-100.0 * Math.sqrt(128), x[0][10][0], 1e-3);
        assertEquals(0f, x[5][10][0], 1e-4);
        for (int t = 0; t < 63; t++) {
            assertEquals(0f, x[0][t][1], 1e-4);
            assertEquals(0f, x[0][t][2], 1e-4);
        }
    }

    private static float[] tone(float hz, int samples) {
        float[] y = new float[samples];
        for (int i = 0; i < samples; i++) {
            y[i] = (float) (0.5 * Math.sin(2 * Math.PI * hz * i / MfccExtractor.SAMPLE_RATE));
        }
        return y;
    }
}
