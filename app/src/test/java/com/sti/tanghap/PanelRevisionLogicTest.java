package com.sti.tanghap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.ai.BreathingAnalyzer;
import com.sti.tanghap.ai.CoughClass;
import com.sti.tanghap.domain.DoseReminder;
import com.sti.tanghap.domain.InhalationTracker;

import org.junit.Test;

public class PanelRevisionLogicTest {

    private static BreathingAnalyzer.BreathingResult bpm(int value) {
        return new BreathingAnalyzer.BreathingResult(value, "", "", "Normal");
    }

    // ---------- Wheezing ----------

    @Test
    public void wheezeFromModel_triggersAsthmaFinding() {
        AssessmentEngine.AssessmentResult r = new AssessmentEngine().generatePreliminaryAssessment(
                bpm(16), CoughClass.WHEEZING, false, false, false);
        assertTrue(r.wheezeDetected);
        assertEquals("Early Detection of Asthma", r.otcCategory);
        assertTrue(r.recommendHospital);
    }

    @Test
    public void noModelPrediction_isStandard() {
        AssessmentEngine.AssessmentResult r = new AssessmentEngine().generatePreliminaryAssessment(
                bpm(16), null, false, false, false);
        assertFalse(r.wheezeDetected);
        assertEquals("Standard Respiratory Evaluation", r.otcCategory);
    }

    @Test
    public void persistentCough_withCopdHistory_givesCopdFinding() {
        AssessmentEngine.AssessmentResult r = new AssessmentEngine().generatePreliminaryAssessment(
                bpm(16), CoughClass.PERSISTENT_COUGH, false, true, false);
        assertEquals("COPD Assessment", r.otcCategory);
    }

    // ---------- Inhalation duration ----------

    @Test
    public void tracker_measuresInhalationLength() {
        InhalationTracker t = new InhalationTracker();
        // 10 samples per packet, one packet every 100 ms -> 10 ms per sample
        float[] quiet = new float[10];
        float[] inhale = new float[10];
        for (int i = 0; i < 10; i++) inhale[i] = -3.0f;   // suction shows as negative pressure

        long time = 0;
        t.addSamples(quiet, time += 100);
        for (int p = 0; p < 20; p++) t.addSamples(inhale, time += 100);   // 200 samples = 2.0 s
        t.addSamples(quiet, time += 100);
        t.addSamples(quiet, time += 100);
        t.finish();

        assertEquals(1, t.getInhalationCount());
        assertEquals(2000, t.getAverageInhaleMillis());
        assertEquals(3.0f, t.getPeakEffortKpa(), 0.001f);
    }

    @Test
    public void tracker_ignoresSingleSampleSpikes() {
        InhalationTracker t = new InhalationTracker();
        t.addSamples(new float[]{0f, 4f, 0f, 0f}, 100);
        t.addSamples(new float[]{0f, 0f, 0f, 0f}, 200);
        t.finish();
        assertEquals(0, t.getInhalationCount());
        assertEquals(0, t.getAverageInhaleMillis());
    }

    // ---------- Missed / late doses ----------

    @Test
    public void dose_noScheduleMeansNoReminder() {
        assertEquals(DoseReminder.Status.NONE, new DoseReminder().evaluate(0, 0, 21 * 60).status);
    }

    @Test
    public void dose_lateAfterGracePeriod() {
        // 2 doses/day are due at 8:00 AM and 2:00 PM; with 1 h grace, 9:30 AM with none taken is late
        DoseReminder.Result r = new DoseReminder().evaluate(2, 0, 9 * 60 + 30);
        assertEquals(DoseReminder.Status.LATE, r.status);
        assertEquals(1, r.expectedSoFar);
    }

    @Test
    public void dose_notLateWithinGracePeriod() {
        assertEquals(DoseReminder.Status.NONE, new DoseReminder().evaluate(2, 0, 8 * 60 + 30).status);
    }

    @Test
    public void dose_onTrackMeansNoReminder() {
        assertEquals(DoseReminder.Status.NONE, new DoseReminder().evaluate(2, 1, 12 * 60).status);
    }

    @Test
    public void dose_missedAfterWindowCloses() {
        DoseReminder.Result r = new DoseReminder().evaluate(2, 1, 21 * 60);
        assertEquals(DoseReminder.Status.MISSED, r.status);
        assertEquals("Missed inhaler dose", r.title);
    }

    @Test
    public void dose_allTakenMeansNoReminder() {
        assertEquals(DoseReminder.Status.NONE, new DoseReminder().evaluate(3, 3, 22 * 60).status);
    }
}
