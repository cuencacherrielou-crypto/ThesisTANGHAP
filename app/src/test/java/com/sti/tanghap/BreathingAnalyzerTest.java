package com.sti.tanghap;

import com.sti.tanghap.ai.BreathingAnalyzer;
import com.sti.tanghap.domain.InhalationTracker;
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

    /** Tidal breathing through the mouthpiece: suction (negative) in, then positive pressure out. */
    private static float[] inAndOut(float bpm, float seconds, float sampleRateHz, float amplitudeKpa) {
        int n = Math.round(seconds * sampleRateHz);
        float[] data = new float[n];
        for (int i = 0; i < n; i++) {
            data[i] = (float) (-amplitudeKpa * Math.sin(2 * Math.PI * bpm / 60.0 * i / sampleRateHz));
        }
        return data;
    }

    @Test
    public void breathInAndOutCountsOnce() {
        // 15 BPM breathing in and out at the device's 10 Hz: 7-8 breaths in 30 s, not 15
        int bpm = new BreathingAnalyzer().analyze(inAndOut(15, 30, 10, 3f), 30f).bpm;
        assertTrue("bpm was " + bpm, bpm >= 14 && bpm <= 16);
    }

    @Test
    public void exhalingOrCoughingIntoTheDeviceIsNotABreath() {
        // Only positive pressure (blowing out, coughing): no inhalations at all
        assertEquals(0, new BreathingAnalyzer().analyze(breathing(15, 30, 10, 5f), 30f).bpm);
    }

    @Test
    public void waveringInhalationCountsOnce() {
        // One 2 s inhalation whose suction dips briefly below the threshold in the middle
        float[] d = new float[300];
        for (int i = 100; i < 120; i++) d[i] = (i >= 109 && i <= 110) ? -1.0f : -3f;
        assertEquals(1, BreathingAnalyzer.countInhalations(d, 1));
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
    public void oneInhalerPuffGivesNoRate() {
        // a single inhalation must not become "2 BPM" (one breath in a 30 s window)
        float[] d = new float[300];
        for (int i = 100; i < 115; i++) d[i] = -1f - (i % 5);
        BreathingAnalyzer.BreathingResult r = new BreathingAnalyzer().analyze(d, 30f);
        assertEquals(0, r.bpm);
        assertEquals("Measuring breathing rate...", r.pattern);
    }

    @Test
    public void quietTimeBeforeBreathingDoesNotSlowTheRate() {
        // 15 s of nothing, then 15 s of breathing at 15 BPM: the rate is 15, not about 8
        float[] quiet = new float[150];
        float[] breaths = breathing(15, 15, 10, -5f);
        float[] d = new float[300];
        System.arraycopy(quiet, 0, d, 0, 150);
        System.arraycopy(breaths, 0, d, 150, 150);
        int bpm = new BreathingAnalyzer().analyze(d, 30f).bpm;
        assertTrue("bpm was " + bpm, bpm >= 14 && bpm <= 16);
    }

    @Test
    public void breathingThatStoppedHasNoCurrentRate() {
        // breathing at 15 BPM for 12 s, then 18 s without a breath
        float[] d = new float[300];
        System.arraycopy(breathing(15, 12, 10, -5f), 0, d, 0, 120);
        BreathingAnalyzer.BreathingResult r = new BreathingAnalyzer().analyze(d, 30f);
        assertEquals(0, r.bpm);
        assertEquals("Breathing paused", r.pattern);
    }

    @Test
    public void frozenSensorValuesAreNotBreaths() {
        // the TANGHAP device's glitch: flat -7.81 kPa for half a second, three times
        float[] d = new float[300];
        for (int start : new int[]{50, 120, 190}) {
            for (int i = start; i < start + 5; i++) d[i] = -7.81f;
        }
        BreathingAnalyzer.BreathingResult r = new BreathingAnalyzer().analyze(d, 30f);
        assertEquals(0, r.bpm);
        assertEquals("Awaiting Inhalation...", r.pattern);
    }

    @Test
    public void flatSignalHasNoBreaths() {
        BreathingAnalyzer.BreathingResult r = new BreathingAnalyzer().analyze(new float[600], 30f);
        assertEquals(0, r.bpm);
    }

    // ---------- Inhalation effort and duration (InhalationTracker) ----------

    /** Feeds one sample per packet every 100 ms, as the TANGHAP firmware sends them. */
    private static InhalationTracker track(float... kpa) {
        InhalationTracker t = new InhalationTracker();
        long time = 0;
        for (float v : kpa) t.addSamples(new float[]{v}, time += 100);
        t.finish();
        return t;
    }

    @Test
    public void coughOrExhaleIsNotInhalationEffort() {
        InhalationTracker t = track(0, 6f, 7f, 6f, 0, 0);   // blowing / coughing into the mouthpiece
        assertEquals(0, t.getInhalationCount());
        assertEquals(0f, t.getPeakEffortKpa(), 0f);
    }

    @Test
    public void inhalationDurationCoversTheWholeBreathIn() {
        // suction ramps 0.5 -> 3 -> 0.5 kPa over 1.0 s; its start and end are below the 0.3 kPa threshold
        InhalationTracker t = track(0, -0.5f, -1f, -1.4f, -2f, -3f, -2f, -1.6f, -1f, -0.8f, -0.5f, 0, 0);
        assertEquals(1, t.getInhalationCount());
        assertEquals(1000L, t.getInhalations().get(0).durationMillis);
        assertEquals(3f, t.getPeakEffortKpa(), 0.001f);
    }

    @Test
    public void weakSuctionBelowThresholdIsNotAnInhalation() {
        InhalationTracker t = track(0, -0.1f, -0.2f, -0.25f, -0.2f, -0.1f, 0, 0);   // peaks below the 0.3 kPa threshold
        assertEquals(0, t.getInhalationCount());
        assertEquals(0f, t.getPeakEffortKpa(), 0f);
    }

    @Test
    public void frozenValueIsASensorGlitchNotAnInhalation() {
        InhalationTracker t = track(0, -7.81f, -7.81f, -7.81f, -7.81f, -7.81f, 0, 0);
        assertEquals(0, t.getInhalationCount());
        assertEquals(1, t.getSensorGlitchCount());
        assertEquals(0f, t.getPeakEffortKpa(), 0f);
    }

    @Test
    public void sensorLimitIsASensorGlitchNotAnInhalation() {
        InhalationTracker t = track(0, -2f, -6f, -10.42f, -6f, -2f, 0, 0);
        assertEquals(0, t.getInhalationCount());
        assertEquals(1, t.getSensorGlitchCount());
    }

    @Test
    public void inhalationIsVisibleWhileItHappens() {
        InhalationTracker t = new InhalationTracker();
        long time = 0;
        for (float v : new float[]{0, 0, -0.5f, -1.2f, -2.4f}) t.addSamples(new float[]{v}, time += 100);
        assertTrue(t.isInhaling());
        assertEquals(300L, t.getCurrentInhaleMillis());
        assertEquals(2.4f, t.getCurrentInhalePeakKpa(), 0.001f);
        assertNull(t.getLastInhalation());

        for (float v : new float[]{-1.0f, 0, 0}) t.addSamples(new float[]{v}, time += 100);
        assertFalse(t.isInhaling());
        assertEquals(400L, t.getLastInhalation().durationMillis);
        assertEquals(2.4f, t.getLastInhalation().peakKpa, 0.001f);
    }

    @Test
    public void flatGlitchIsNeverShownAsInhaling() {
        // The faulty HX710B on 2026-10-26: flat jumps of exactly -2.60 kPa
        InhalationTracker t = new InhalationTracker();
        long time = 0;
        for (float v : new float[]{0, -2.6f, -2.6f, -2.6f, -2.6f}) {
            t.addSamples(new float[]{v}, time += 100);
            assertFalse(t.isInhaling());
            assertEquals(0L, t.getCurrentInhaleMillis());
        }
        t.addSamples(new float[]{0f}, time += 100);
        assertEquals(0, t.getInhalationCount());
        assertEquals(1, t.getSensorGlitchCount());
    }

    @Test
    public void singleSampleSpikeDoesNotSetPeakEffort() {
        InhalationTracker t = track(0, -9f, 0, 0, -0.5f, -2f, -2.5f, -0.5f, 0, 0);
        assertEquals(1, t.getInhalationCount());
        assertEquals(2.5f, t.getPeakEffortKpa(), 0.001f);
    }
}
