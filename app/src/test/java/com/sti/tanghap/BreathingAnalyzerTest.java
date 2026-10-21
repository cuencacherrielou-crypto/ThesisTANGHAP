package com.sti.tanghap;

import com.sti.tanghap.ai.BreathingAnalyzer;
import org.junit.Test;
import static org.junit.Assert.*;

public class BreathingAnalyzerTest {

    /** Smooth breathing signal: each breath is a raised-cosine pressure pulse of the given sign. */
    private static float[] breathing(float bpm, float seconds, float sampleRateHz, float amplitudeKpa) {
        int n = Math.round(seconds * sampleRateHz);
        float[] data = new float[n];
        double breathHz = bpm / 60.0;
        for (int i = 0; i < n; i++) {
            double t = i / sampleRateHz;
            data[i] = (float) (amplitudeKpa * (0.5 - 0.5 * Math.cos(2 * Math.PI * breathHz * t)));
        }
        return data;
    }

    @Test
    public void bpmUsesRealDuration() {
        // 15 BPM for 30 s -> 7 complete breath peaks in the window -> about 14-16 BPM
        BreathingAnalyzer.BreathingResult r = new BreathingAnalyzer().analyze(breathing(15, 30, 20, -5f), 30f);
        assertTrue("bpm was " + r.bpm, r.bpm >= 13 && r.bpm <= 16);
        assertEquals("Normal Breathing", r.pattern);
    }

    @Test
    public void bpmDoesNotDependOnSampleRate() {
        BreathingAnalyzer analyzer = new BreathingAnalyzer();
        int slow = analyzer.analyze(breathing(15, 30, 10, -5f), 30f).bpm;
        int fast = analyzer.analyze(breathing(15, 30, 100, -5f), 30f).bpm;
        assertEquals(slow, fast);
    }

    @Test
    public void bpmDoesNotDependOnPressureSign() {
        BreathingAnalyzer analyzer = new BreathingAnalyzer();
        int negative = analyzer.analyze(breathing(15, 30, 20, -5f), 30f).bpm;
        int positive = analyzer.analyze(breathing(15, 30, 20, 5f), 30f).bpm;
        assertEquals(negative, positive);
    }

    @Test
    public void rapidBreathingIsDetected() {
        BreathingAnalyzer.BreathingResult r = new BreathingAnalyzer().analyze(breathing(30, 30, 20, -5f), 30f);
        assertTrue("bpm was " + r.bpm, r.bpm >= 28 && r.bpm <= 31);
        assertEquals("High", r.severity);
    }

    @Test
    public void shortWindowReportsNoRateYet() {
        BreathingAnalyzer.BreathingResult r = new BreathingAnalyzer().analyze(breathing(15, 5, 20, -5f), 5f);
        assertEquals(0, r.bpm);
    }

    @Test
    public void flatSignalHasNoBreaths() {
        BreathingAnalyzer.BreathingResult r = new BreathingAnalyzer().analyze(new float[600], 30f);
        assertEquals(0, r.bpm);
    }
}
