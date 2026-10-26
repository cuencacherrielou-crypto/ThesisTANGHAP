package com.sti.tanghap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.ai.BreathingAnalyzer;
import com.sti.tanghap.ai.CoughClass;
import com.sti.tanghap.domain.DoseReminder;

import org.junit.Test;

public class PanelRevisionLogicTest {

    private static BreathingAnalyzer.BreathingResult bpm(int value) {
        return new BreathingAnalyzer.BreathingResult(value, "", "", "Normal");
    }

    // ---------- Wheezing ----------

    @Test
    public void singleWheezeFromModel_isNotedButNamesNoCondition() {
        AssessmentEngine.AssessmentResult r = new AssessmentEngine().generatePreliminaryAssessment(
                bpm(16), CoughClass.WHEEZING, false, false, false);
        assertTrue(r.wheezeDetected);
        // Asthma needs repeated, recurring wheezing with another symptom (AssessmentEngineTest)
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
        assertFalse(r.recommendHospital);
    }

    @Test
    public void singleWheezeWithRapidBreathing_isNotHospital() {
        AssessmentEngine.AssessmentResult r = new AssessmentEngine().generatePreliminaryAssessment(
                bpm(26), CoughClass.WHEEZING, false, false, false);
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
        assertFalse(r.recommendHospital);
        assertTrue(r.recommendClinic);   // the rapid breathing is still checked
    }

    @Test
    public void noModelPrediction_isStandard() {
        AssessmentEngine.AssessmentResult r = new AssessmentEngine().generatePreliminaryAssessment(
                bpm(16), null, false, false, false);
        assertFalse(r.wheezeDetected);
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
    }

    @Test
    public void persistentCough_withCopdHistory_needsASecondSign() {
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, new AssessmentEngine().generatePreliminaryAssessment(
                bpm(16), CoughClass.PERSISTENT_COUGH, false, true, false).possibleCondition);
        // with breathlessness (rapid breathing) as well: a possible flare-up
        assertEquals(AssessmentEngine.CATEGORY_COPD, new AssessmentEngine().generatePreliminaryAssessment(
                bpm(22), CoughClass.PERSISTENT_COUGH, false, true, false).possibleCondition);
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
