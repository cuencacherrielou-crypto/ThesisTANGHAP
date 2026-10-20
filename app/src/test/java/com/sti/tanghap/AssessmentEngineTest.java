package com.sti.tanghap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.ai.BreathingAnalyzer;
import com.sti.tanghap.domain.CoughAnalyzer;

import org.junit.Test;

public class AssessmentEngineTest {

    private final AssessmentEngine engine = new AssessmentEngine();

    private BreathingAnalyzer.BreathingResult bpm(int value) {
        return new BreathingAnalyzer.BreathingResult(value, "", "", "Normal");
    }

    @Test
    public void copdUserWithFrequentCough_getsCopdFinding() {
        AssessmentEngine.AssessmentResult r = engine.generatePreliminaryAssessment(
                bpm(16), CoughAnalyzer.CoughType.FREQUENT, false, true, false);
        assertEquals("COPD Assessment", r.otcCategory);
        assertTrue(r.recommendClinic);
        assertFalse(r.recommendHospital);
    }

    @Test
    public void copdUserWithRapidBreathing_getsCopdFinding() {
        AssessmentEngine.AssessmentResult r = engine.generatePreliminaryAssessment(
                bpm(22), CoughAnalyzer.CoughType.NORMAL, false, true, false);
        assertEquals("COPD Assessment", r.otcCategory);
    }

    @Test
    public void userWithoutCopd_wetCough_staysBronchitis() {
        AssessmentEngine.AssessmentResult r = engine.generatePreliminaryAssessment(
                bpm(16), CoughAnalyzer.CoughType.WET, false, false, false);
        assertEquals("Bronchitis Pattern Recognition", r.otcCategory);
    }

    @Test
    public void severeCough_staysPneumoniaEvenWithCopd() {
        AssessmentEngine.AssessmentResult r = engine.generatePreliminaryAssessment(
                bpm(16), CoughAnalyzer.CoughType.SEVERE, false, true, false);
        assertEquals("Pneumonia Symptoms Indication", r.otcCategory);
        assertTrue(r.recommendHospital);
    }

    @Test
    public void copdUserWithStableReadings_staysBaseline() {
        AssessmentEngine.AssessmentResult r = engine.generatePreliminaryAssessment(
                bpm(16), CoughAnalyzer.CoughType.NORMAL, false, true, false);
        assertEquals("Standard Respiratory Evaluation", r.otcCategory);
    }

    @Test
    public void allergyHistory_doesNotRecommendMedicine() {
        AssessmentEngine.AssessmentResult r = engine.generatePreliminaryAssessment(
                bpm(16), CoughAnalyzer.CoughType.NORMAL, false, false, true);
        assertEquals("Standard Respiratory Evaluation", r.otcCategory);
        assertFalse(r.recommendPharmacy);
    }
}
