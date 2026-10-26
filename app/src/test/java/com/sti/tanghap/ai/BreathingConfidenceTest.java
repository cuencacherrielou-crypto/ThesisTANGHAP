package com.sti.tanghap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.sti.tanghap.ai.BreathSoundAnalyzer.Result;
import com.sti.tanghap.ai.BreathSoundAnalyzer.Rule;
import com.sti.tanghap.ai.BreathSoundAnalyzer.State;

import org.junit.Test;

/** The breathing pattern's confidence, and which patterns may change the advice. */
public class BreathingConfidenceTest {

    private static final int ADULT = 30;

    @Test
    public void highConfidencePatternIsShownAndUsedForTheAdvice() {
        BreathingAnalyzer.BreathingResult r = BreathingAnalyzer.fromSounds(new Result(State.MEASURED, 30, Rule.LOUD_PHASE), ADULT);
        assertEquals("Rapid Breathing (Tachypnea-like) · High confidence", r.pattern);
        assertEquals(30, r.bpm);
        assertEquals("Rapid", BreathingAnalyzer.shortLabel(r.pattern));
    }

    @Test
    public void mediumConfidencePatternIsInformationOnly() {
        BreathingAnalyzer.BreathingResult r = BreathingAnalyzer.fromSounds(new Result(State.MEASURED, 8, Rule.TWO_PHASES), ADULT);
        assertEquals("Slow Breathing (Bradypnea-like) · Medium confidence", r.pattern);
        assertEquals(0, r.bpm);   // not used for the advice
        assertTrue(r.insight.contains("not used for the advice"));
    }

    @Test
    public void lowConfidenceRapidIsShownButNotUsed() {
        // 13:32:07 device session: deliberate fast breathing, one sound per breath
        BreathingAnalyzer.BreathingResult r = BreathingAnalyzer.fromSounds(new Result(State.MEASURED, 40, Rule.ONE_PHASE), ADULT);
        assertEquals("Rapid Breathing (Tachypnea-like) · Low confidence", r.pattern);
        assertEquals(0, r.bpm);
    }

    @Test
    public void lowConfidenceNormalIsNotShown() {
        BreathingAnalyzer.BreathingResult r = BreathingAnalyzer.fromSounds(new Result(State.MEASURED, 16, Rule.ONE_PHASE), ADULT);
        assertEquals(BreathingAnalyzer.NOT_CLEAR_PATTERN, r.pattern);
        assertEquals(0, r.bpm);
    }

    @Test
    public void onlyHighConfidenceFastBreathingWithCoughsGivesHospitalAdvice() {
        AssessmentEngine engine = new AssessmentEngine();
        PatientProfile adult = new PatientProfile(ADULT, false, false, false);
        java.util.List<com.sti.tanghap.data.SessionEvent> coughs = java.util.Arrays.asList(
                new com.sti.tanghap.data.SessionEvent(1_000, com.sti.tanghap.data.SessionEvent.Type.COUGH, 80, CoughClass.DRY_COUGH.label),
                new com.sti.tanghap.data.SessionEvent(9_000, com.sti.tanghap.data.SessionEvent.Type.COUGH, 80, CoughClass.DRY_COUGH.label));
        CoughEvidence ev = CoughEvidence.from(coughs, java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), 10_000, java.time.ZoneOffset.UTC);
        assertTrue(engine.assess(BreathingAnalyzer.fromSounds(
                new Result(State.MEASURED, 30, Rule.LOUD_PHASE), ADULT), ev, adult).recommendHospital);
        assertTrue(!engine.assess(BreathingAnalyzer.fromSounds(
                new Result(State.MEASURED, 30, Rule.ONE_PHASE), ADULT), ev, adult).recommendHospital);
    }
}
