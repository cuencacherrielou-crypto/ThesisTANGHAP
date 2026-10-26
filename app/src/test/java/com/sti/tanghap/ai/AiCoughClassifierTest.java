package com.sti.tanghap.ai;

import static org.junit.Assert.assertEquals;

import com.sti.tanghap.ai.processor.MfccExtractor;
import com.sti.tanghap.ai.processor.RumbleFilter;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class AiCoughClassifierTest {

    @Test
    public void persistentIsNeverPickedFromOneCough() {
        // order: dry, non, persistent, severe, wet, wheezing
        assertEquals(CoughClass.WET_COUGH.ordinal(), AiCoughClassifier.best(new float[]{0.05f, 0.05f, 0.6f, 0.05f, 0.2f, 0.05f}));
        assertEquals(CoughClass.NON_COUGH.ordinal(), AiCoughClassifier.best(new float[]{0.1f, 0.4f, 0.3f, 0.1f, 0.05f, 0.05f}));
        assertEquals(CoughClass.DRY_COUGH.ordinal(), AiCoughClassifier.best(new float[]{0.7f, 0.1f, 0.1f, 0.05f, 0.03f, 0.02f}));
    }

    /**
     * A real cough (COUGHVID, CC BY 4.0) gives the same model input on the phone as in training
     * (rumble filter, then librosa MFCC + deltas); the expected values were computed by the
     * training pipeline.
     */
    @Test
    public void realCoughGivesTheTrainingFeatures() throws IOException {
        byte[] pcm = readAll("/cough_clip_16k.pcm");
        float[][][] x = new MfccExtractor().extract(RumbleFilter.apply(AiCoughClassifier.toFloat(pcm)));
        List<Float> expected = readFloats("/cough_clip_features.txt");
        assertEquals(20 * 63 * 3, expected.size());
        int i = 0;
        for (int c = 0; c < 20; c++) {
            for (int t = 0; t < 63; t++) {
                for (int k = 0; k < 3; k++) {
                    float e = expected.get(i++);
                    assertEquals("mfcc " + c + " frame " + t + " order " + k, e, x[c][t][k], 0.02f + 1e-3f * Math.abs(e));
                }
            }
        }
    }

    private static byte[] readAll(String resource) throws IOException {
        try (InputStream is = AiCoughClassifierTest.class.getResourceAsStream(resource)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) != -1) out.write(chunk, 0, n);
            return out.toByteArray();
        }
    }

    private static List<Float> readFloats(String resource) throws IOException {
        List<Float> values = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                AiCoughClassifierTest.class.getResourceAsStream(resource)))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) values.add(Float.parseFloat(line));
            }
        }
        return values;
    }
}
