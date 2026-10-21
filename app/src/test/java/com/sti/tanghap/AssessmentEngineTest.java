package com.sti.tanghap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.ai.BreathingAnalyzer;
import com.sti.tanghap.ai.CoughClass;

import org.junit.Test;

public class AssessmentEngineTest {

    private final AssessmentEngine engine = new AssessmentEngine();

    private BreathingAnalyzer.BreathingResult bpm(int value) {
        return new BreathingAnalyzer.BreathingResult(value, "", "", "Normal");
    }

    @Test
    public void copdUserWithPersistentCough_getsCopdFinding() {
        AssessmentEngine.AssessmentResult r = engine.generatePreliminaryAssessment(
                bpm(16), CoughClass.PERSISTENT_COUGH, false, true, false);
        assertEquals("COPD Assessment", r.otcCategory);
        assertTrue(r.recommendClinic);
        assertFalse(r.recommendHospital);
    }

    @Test
    public void copdUserWithRapidBreathing_getsCopdFinding() {
        AssessmentEngine.AssessmentResult r = engine.generatePreliminaryAssessment(
                bpm(22), null, false, true, false);
        assertEquals("COPD Assessment", r.otcCategory);
    }

    @Test
    public void userWithoutCopd_wetCough_staysBronchitis() {
        AssessmentEngine.AssessmentResult r = engine.generatePreliminaryAssessment(
                bpm(16), CoughClass.WET_COUGH, false, false, false);
        assertEquals("Bronchitis Pattern Recognition", r.otcCategory);
    }

    @Test
    public void severeCough_staysPneumoniaEvenWithCopd() {
        AssessmentEngine.AssessmentResult r = engine.generatePreliminaryAssessment(
                bpm(16), CoughClass.SEVERE_COUGH, false, true, false);
        assertEquals("Pneumonia Symptoms Indication", r.otcCategory);
        assertTrue(r.recommendHospital);
    }

    @Test
    public void copdUserWithStableReadings_staysBaseline() {
        AssessmentEngine.AssessmentResult r = engine.generatePreliminaryAssessment(
                bpm(16), null, false, true, false);
        assertEquals("Standard Respiratory Evaluation", r.otcCategory);
    }

    @Test
    public void allergyHistory_doesNotRecommendMedicine() {
        AssessmentEngine.AssessmentResult r = engine.generatePreliminaryAssessment(
                bpm(16), null, false, false, true);
        assertEquals("Standard Respiratory Evaluation", r.otcCategory);
        assertFalse(r.recommendPharmacy);
    }

    // ---------- Unified rule set (single engine for live + saved session) ----------

    private AssessmentEngine.AssessmentResult assess(int bpm, CoughClass cough, boolean asthma, boolean copd) {
        return engine.generatePreliminaryAssessment(BreathingAnalyzer.classify(bpm), cough, asthma, copd, false);
    }

    @Test
    public void normalBreathingIsStandard() {
        AssessmentEngine.AssessmentResult r = assess(16, null, false, false);
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.otcCategory);
        assertFalse(r.recommendHospital);
    }

    @Test
    public void normalRateIsNotBronchitis() {
        // 19 BPM is inside the normal 12-20 range
        AssessmentEngine.AssessmentResult r = assess(19, null, false, false);
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.otcCategory);
    }

    @Test
    public void observationsOnlyDescribeMeasuredData() {
        AssessmentEngine.AssessmentResult r = assess(26, null, false, false);
        assertEquals(AssessmentEngine.CATEGORY_PNEUMONIA, r.otcCategory);
        assertTrue(r.recommendHospital);
        for (String o : r.observations) {
            assertFalse(o, o.toLowerCase().contains("mucus"));
            assertFalse(o, o.toLowerCase().contains("cough"));
        }
    }

    @Test
    public void wheezingFromModelIsAsthmaLike() {
        AssessmentEngine.AssessmentResult r = assess(16, CoughClass.WHEEZING, false, false);
        assertEquals(AssessmentEngine.CATEGORY_ASTHMA, r.otcCategory);
    }

    @Test
    public void persistentCoughWithCopdHistoryIsCopd() {
        AssessmentEngine.AssessmentResult r = assess(16, CoughClass.PERSISTENT_COUGH, false, true);
        assertEquals(AssessmentEngine.CATEGORY_COPD, r.otcCategory);
    }

    @Test
    public void wetCoughIsBronchitisPattern() {
        AssessmentEngine.AssessmentResult r = assess(16, CoughClass.WET_COUGH, false, false);
        assertEquals(AssessmentEngine.CATEGORY_BRONCHITIS, r.otcCategory);
        assertFalse(r.recommendHospital);
    }

    @Test
    public void persistentCoughWithAsthmaHistoryIsAsthmaLike() {
        AssessmentEngine.AssessmentResult r = assess(16, CoughClass.PERSISTENT_COUGH, true, false);
        assertEquals(AssessmentEngine.CATEGORY_ASTHMA, r.otcCategory);
    }

    @Test
    public void dryCoughGetsCoughSuppressants() {
        AssessmentEngine.AssessmentResult r = assess(16, CoughClass.DRY_COUGH, false, false);
        assertEquals("Cough Suppressants (for non-productive cough)", r.otcCategory);
        assertTrue(r.recommendPharmacy);
    }

    @Test
    public void nonCoughSoundIsStandard() {
        AssessmentEngine.AssessmentResult r = assess(16, CoughClass.NON_COUGH, false, false);
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.otcCategory);
        assertFalse(r.recommendPharmacy);
    }

    @Test
    public void insightContainsGuidance() {
        AssessmentEngine.AssessmentResult r = assess(16, null, false, false);
        assertTrue(r.insight.contains(r.guidance));
    }
}
