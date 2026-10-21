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

    // ---------- Unified rule set (single engine for live + saved session) ----------

    private AssessmentEngine.AssessmentResult assess(int bpm, CoughAnalyzer.CoughType type, String label,
                                                     int coughs, boolean asthma, boolean copd) {
        return engine.generatePreliminaryAssessment(
                BreathingAnalyzer.classify(bpm), type, label, coughs, asthma, copd, false);
    }

    @Test
    public void normalBreathingIsStandard() {
        AssessmentEngine.AssessmentResult r = assess(16, CoughAnalyzer.CoughType.NORMAL, null, 0, false, false);
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.otcCategory);
        assertFalse(r.recommendHospital);
    }

    @Test
    public void normalRateIsNotBronchitis() {
        // 19 BPM is inside the normal 12-20 range
        AssessmentEngine.AssessmentResult r = assess(19, CoughAnalyzer.CoughType.NORMAL, null, 0, false, false);
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.otcCategory);
    }

    @Test
    public void observationsOnlyDescribeMeasuredData() {
        AssessmentEngine.AssessmentResult r = assess(26, CoughAnalyzer.CoughType.NORMAL, null, 0, false, false);
        assertEquals(AssessmentEngine.CATEGORY_PNEUMONIA, r.otcCategory);
        assertTrue(r.recommendHospital);
        for (String o : r.observations) {
            assertFalse(o, o.toLowerCase().contains("mucus"));
            assertFalse(o, o.toLowerCase().contains("cough"));
        }
    }

    @Test
    public void wheezingLabelFromModelIsAsthmaLike() {
        AssessmentEngine.AssessmentResult r = assess(16, CoughAnalyzer.CoughType.DRY, "Wheezing", 1, false, false);
        assertEquals(AssessmentEngine.CATEGORY_ASTHMA, r.otcCategory);
    }

    @Test
    public void persistentCoughWithCopdHistoryIsCopd() {
        AssessmentEngine.AssessmentResult r = assess(16, CoughAnalyzer.CoughType.DRY, "Persistent cough", 2, false, true);
        assertEquals(AssessmentEngine.CATEGORY_COPD, r.otcCategory);
    }

    @Test
    public void wetCoughIsBronchitisPattern() {
        AssessmentEngine.AssessmentResult r = assess(16, CoughAnalyzer.CoughType.DRY, "Wet cough", 1, false, false);
        assertEquals(AssessmentEngine.CATEGORY_BRONCHITIS, r.otcCategory);
        assertFalse(r.recommendHospital);
    }

    @Test
    public void insightContainsGuidance() {
        AssessmentEngine.AssessmentResult r = assess(16, CoughAnalyzer.CoughType.NORMAL, null, 0, false, false);
        assertTrue(r.insight.contains(r.guidance));
    }
}
