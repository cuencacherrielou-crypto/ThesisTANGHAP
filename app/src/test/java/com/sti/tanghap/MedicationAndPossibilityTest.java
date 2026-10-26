package com.sti.tanghap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.ai.BreathingAnalyzer;
import com.sti.tanghap.ai.CoughClass;
import com.sti.tanghap.ai.CoughEvidence;
import com.sti.tanghap.ai.OtcGuidance;
import com.sti.tanghap.ai.PatientProfile;
import com.sti.tanghap.ai.SavedAssessment;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.data.SessionEvent;

import org.junit.Test;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The medicine shown must match the finding, and conditions are only named as possibilities. */
public class MedicationAndPossibilityTest {

    private final AssessmentEngine engine = new AssessmentEngine();

    private AssessmentEngine.AssessmentResult assess(int bpm, CoughClass cough, PatientProfile p) {
        return engine.generatePreliminaryAssessment(BreathingAnalyzer.classify(bpm, p.ageYears), cough, p);
    }

    /** {@code n} sounds of one type in this session (a condition needs several signs, never one sound). */
    private AssessmentEngine.AssessmentResult assess(int bpm, int n, CoughClass type, PatientProfile p) {
        List<SessionEvent> events = new ArrayList<>();
        SessionEvent.Type t = type == CoughClass.WHEEZING ? SessionEvent.Type.WHEEZE : SessionEvent.Type.COUGH;
        for (int i = 1; i <= n; i++) events.add(new SessionEvent(10_000L * i, t, 80, type.label));
        CoughEvidence ev = CoughEvidence.from(events, Collections.emptyList(), Collections.emptyList(),
                10_000L * (n + 1), ZoneOffset.UTC);
        return engine.assess(BreathingAnalyzer.classify(bpm, p.ageYears), ev, p);
    }

    private static PatientProfile adult() {
        return new PatientProfile(30, false, false, false);
    }

    private static String lower(String s) {
        return s.toLowerCase();
    }

    // ---------- Medicine matches the finding ----------

    @Test
    public void dryCough_getsDextromethorphan_notExpectorant() {
        AssessmentEngine.AssessmentResult r = assess(16, CoughClass.DRY_COUGH, adult());
        assertEquals(OtcGuidance.DRY_COUGH, r.medicineKey);
        assertTrue(r.medicationGuidance.contains("Dextromethorphan"));
        assertTrue(r.medicationGuidance.contains("MAOI"));
        assertTrue(r.medicationGuidance.contains("Do not use expectorants"));
        assertFalse(r.medicationGuidance.contains("Guaifenesin (expectorant)"));
        assertTrue(r.recommendPharmacy);
    }

    @Test
    public void wetCough_getsExpectorant_notSuppressant() {
        AssessmentEngine.AssessmentResult r = assess(16, CoughClass.WET_COUGH, adult());
        // A wet cough alone names no condition, but still gets wet-cough guidance
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
        assertEquals(OtcGuidance.WET_COUGH, r.medicineKey);
        assertTrue(r.medicationGuidance.contains("Guaifenesin"));
        assertTrue(r.medicationGuidance.contains("Do not use a cough suppressant"));
        assertFalse(r.medicationGuidance.contains("Dextromethorphan (cough suppressant): reduces"));
    }

    @Test
    public void persistentCough_getsDoctorAndTbAdvice_noMedicine() {
        AssessmentEngine.AssessmentResult r = assess(16, CoughClass.PERSISTENT_COUGH, adult());
        assertEquals(OtcGuidance.PERSISTENT_COUGH, r.medicineKey);
        assertTrue(r.medicationGuidance.contains("TB"));
        assertTrue(r.medicationGuidance.contains("No OTC medicine"));
        assertFalse(r.recommendPharmacy);
    }

    @Test
    public void pneumoniaLike_noMedicine_seekCare() {
        AssessmentEngine.AssessmentResult r = assess(26, 2, CoughClass.SEVERE_COUGH, adult());
        assertEquals(AssessmentEngine.CATEGORY_PNEUMONIA, r.possibleCondition);
        assertEquals(OtcGuidance.SEEK_CARE, r.medicineKey);
        assertTrue(r.medicationGuidance.contains("Seek medical care now"));
        assertTrue(r.medicationGuidance.contains("antibiotics"));
        assertFalse(r.recommendPharmacy);
    }

    @Test
    public void wheeze_getsPrescribedReliever_noOtc() {
        PatientProfile p = adult();
        p.hasAsthma = true;
        AssessmentEngine.AssessmentResult r = assess(16, 2, CoughClass.WHEEZING, p);
        assertEquals(AssessmentEngine.CATEGORY_ASTHMA, r.possibleCondition);
        assertEquals(OtcGuidance.PRESCRIBED_RELIEVER, r.medicineKey);
        assertTrue(r.medicationGuidance.contains("Never use another person's inhaler"));
        assertFalse(r.recommendPharmacy);
    }

    @Test
    public void copd_getsActionPlan_noSuppressant() {
        PatientProfile p = adult();
        p.hasCopd = true;
        AssessmentEngine.AssessmentResult r = assess(22, 3, CoughClass.WET_COUGH, p);   // phlegm + rapid breathing
        assertEquals(AssessmentEngine.CATEGORY_COPD, r.possibleCondition);
        assertEquals(OtcGuidance.COPD_ACTION_PLAN, r.medicineKey);
        assertTrue(r.medicationGuidance.contains("COPD action plan"));
    }

    @Test
    public void noCough_noMedicine() {
        AssessmentEngine.AssessmentResult r = assess(16, null, adult());
        assertEquals(OtcGuidance.NO_MEDICINE, r.medicineKey);
        assertFalse(r.recommendPharmacy);
    }

    @Test
    public void allergies_neverGetMedicine() {
        PatientProfile p = adult();
        p.hasCongestion = true;
        AssessmentEngine.AssessmentResult r = assess(16, null, p);
        assertEquals(OtcGuidance.NO_MEDICINE, r.medicineKey);
        assertFalse(lower(r.medicationGuidance).contains("cetirizine"));
        assertFalse(lower(r.medicationGuidance).contains("loratadine"));
    }

    @Test
    public void childUnder6_neverGetsCoughMedicine() {
        PatientProfile child = new PatientProfile(4, false, false, false);
        for (CoughClass c : new CoughClass[]{CoughClass.DRY_COUGH, CoughClass.WET_COUGH, CoughClass.PERSISTENT_COUGH}) {
            AssessmentEngine.AssessmentResult r = assess(28, c, child);
            assertEquals(c.name(), OtcGuidance.CHILD_UNDER_6, r.medicineKey);
            assertFalse(r.medicationGuidance.contains("Dextromethorphan"));
            assertFalse(r.medicationGuidance.contains("Guaifenesin"));
            assertFalse(r.recommendPharmacy);
        }
    }

    @Test
    public void asthmaHistory_addsAskDoctorWarningToMedicine() {
        PatientProfile p = adult();
        p.hasAsthma = true;
        AssessmentEngine.AssessmentResult r = assess(16, CoughClass.DRY_COUGH, p);
        assertTrue(r.medicationGuidance.contains("asthma or COPD"));
    }

    @Test
    public void everyGuidance_saysRecommendationOnly() {
        for (CoughClass c : CoughClass.values()) {
            AssessmentEngine.AssessmentResult r = assess(16, c, adult());
            assertTrue(c.name(), r.medicationGuidance.endsWith(OtcGuidance.RECOMMENDATION_ONLY));
        }
    }

    // ---------- "Possibility" wording, never a diagnosis ----------

    @Test
    public void everyCondition_isWordedAsPossibility() {
        int[] rates = {16, 26};
        for (int bpm : rates) {
            for (CoughClass c : CoughClass.values()) {
                for (boolean asthma : new boolean[]{false, true}) {
                    for (boolean copd : new boolean[]{false, true}) {
                        PatientProfile p = new PatientProfile(30, asthma, copd, false);
                        AssessmentEngine.AssessmentResult r = assess(bpm, c, p);
                        if (AssessmentEngine.CATEGORY_STANDARD.equals(r.possibleCondition)) continue;
                        assertTrue(r.possibleCondition, r.possibleCondition.startsWith("Possibility of"));
                        assertTrue(r.insight.contains(AssessmentEngine.NOT_A_DIAGNOSIS));
                        String text = lower(r.insight + " " + String.join(" ", r.observations));
                        assertFalse(text, text.contains("you have"));
                        assertFalse(text, text.contains("diagnosed with"));
                    }
                }
            }
        }
    }

    // ---------- Profile answers change the assessment ----------

    @Test
    public void ageChangesBreathingLimits() {
        // 26 BPM is fast for an adult but normal for an 8-year-old (18-30)
        assertEquals(AssessmentEngine.CATEGORY_PNEUMONIA, assess(26, 2, CoughClass.DRY_COUGH, adult()).possibleCondition);
        AssessmentEngine.AssessmentResult child = assess(26, 2, CoughClass.DRY_COUGH, new PatientProfile(8, false, false, false));
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, child.possibleCondition);
        assertFalse(child.recommendClinic);
    }

    @Test
    public void teenager_usesTeenRange() {
        AssessmentEngine.AssessmentResult r = assess(18, null, new PatientProfile(15, false, false, false));
        assertTrue(r.recommendClinic); // 12-16 is normal at 15
    }

    @Test
    public void smokerAndHospitalHistory_addedToConcerningFindings() {
        PatientProfile p = adult();
        p.smokesOrVapes = true;
        p.previouslyHospitalized = true;
        String obs = String.join(" ", assess(16, CoughClass.PERSISTENT_COUGH, p).observations);   // see a doctor
        assertTrue(obs.contains("smokes or vapes"));
        assertTrue(obs.contains("past hospital stay"));
        // Not added when nothing is wrong
        String normal = String.join(" ", assess(16, null, p).observations);
        assertFalse(normal.contains("smokes or vapes"));
    }

    // ---------- Older saved sessions ----------

    @Test
    public void legacySession_mappedToPossibilityAndMedicine() {
        SessionEntity s = new SessionEntity("old");
        s.otcRecommendation = "Bronchitis Pattern Recognition";
        s.coughFinding = "Persistent cough (80% confidence)";
        SavedAssessment saved = SavedAssessment.of(s, adult());
        assertEquals(AssessmentEngine.CATEGORY_BRONCHITIS, saved.possibleCondition);
        assertNotNull(saved.medicationGuidance);
        assertTrue(saved.medicationGuidance.contains("TB"));

        s.otcRecommendation = "Cough Suppressants (for non-productive cough)";
        assertTrue(SavedAssessment.of(s, adult()).medicationGuidance.contains("Dextromethorphan"));
    }

    @Test
    public void newSession_usesStoredText() {
        SessionEntity s = new SessionEntity("new");
        s.possibleCondition = AssessmentEngine.CATEGORY_ASTHMA;
        s.otcRecommendation = "stored guidance";
        SavedAssessment saved = SavedAssessment.of(s, null);
        assertEquals(AssessmentEngine.CATEGORY_ASTHMA, saved.possibleCondition);
        assertEquals("stored guidance", saved.medicationGuidance);
    }
}
