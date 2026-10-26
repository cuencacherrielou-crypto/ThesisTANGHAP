package com.sti.tanghap;

import static org.junit.Assert.assertEquals;

import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.ai.CareAction;
import com.sti.tanghap.domain.BatteryAlert;

import org.junit.Test;

public class BatteryAlertAndCareActionTest {

    // ---------- Low battery notification ----------

    @Test
    public void battery_aboveLimit_noAlert() {
        assertEquals(BatteryAlert.LEVEL_NONE, BatteryAlert.toAnnounce(BatteryAlert.LEVEL_NONE, 50));
    }

    @Test
    public void battery_alertsOnceAtLowThenOnceAtCritical() {
        int announced = BatteryAlert.LEVEL_NONE;
        int[] readings = {25, 20, 19, 21, 18, 10, 9, 8};
        int lows = 0, criticals = 0;
        for (int percent : readings) {
            int level = BatteryAlert.toAnnounce(announced, percent);
            if (level == BatteryAlert.LEVEL_LOW) lows++;
            if (level == BatteryAlert.LEVEL_CRITICAL) criticals++;
            announced = BatteryAlert.remember(announced, percent);
        }
        assertEquals(1, lows);
        assertEquals(1, criticals);
    }

    @Test
    public void battery_alertsAgainOnlyAfterCharging() {
        int announced = BatteryAlert.remember(BatteryAlert.LEVEL_NONE, 15);
        // Wavering just above the limit does not reset it
        announced = BatteryAlert.remember(announced, 28);
        assertEquals(BatteryAlert.LEVEL_NONE, BatteryAlert.toAnnounce(announced, 19));
        // Charged above 30%: the next drop is announced again
        announced = BatteryAlert.remember(announced, 80);
        assertEquals(BatteryAlert.LEVEL_LOW, BatteryAlert.toAnnounce(announced, 19));
    }

    @Test
    public void battery_firstReadingAlreadyCritical_givesCriticalAlert() {
        assertEquals(BatteryAlert.LEVEL_CRITICAL, BatteryAlert.toAnnounce(BatteryAlert.LEVEL_NONE, 5));
    }

    // ---------- Headline of the assessment ----------

    @Test
    public void careAction_followsSavedRecommendation() {
        assertEquals(CareAction.HOSPITAL, CareAction.of(true, AssessmentEngine.GUIDANCE_HOSPITAL, AssessmentEngine.CATEGORY_PNEUMONIA));
        assertEquals(CareAction.CLINIC, CareAction.of(false, AssessmentEngine.GUIDANCE_CLINIC, AssessmentEngine.CATEGORY_ASTHMA));
        assertEquals(CareAction.MONITOR, CareAction.of(false, AssessmentEngine.GUIDANCE_ROUTINE, AssessmentEngine.CATEGORY_STANDARD));
    }

    @Test
    public void careAction_olderSessionWithoutGuidance_usesCondition() {
        assertEquals(CareAction.CLINIC, CareAction.of(false, null, AssessmentEngine.CATEGORY_BRONCHITIS));
        assertEquals(CareAction.MONITOR, CareAction.of(false, null, AssessmentEngine.CATEGORY_STANDARD));
        assertEquals(CareAction.MONITOR, CareAction.of(false, null, null));
    }

    @Test
    public void careAction_matchesEngineForEveryBranch() {
        AssessmentEngine engine = new AssessmentEngine();
        // Persistent cough -> clinic; coughs + fast breathing -> hospital; nothing -> monitor
        AssessmentEngine.AssessmentResult persistent = engine.generatePreliminaryAssessment(
                new com.sti.tanghap.ai.BreathingAnalyzer.BreathingResult(16, "", "", "Normal"),
                com.sti.tanghap.ai.CoughClass.PERSISTENT_COUGH, false, false, false);
        assertEquals(CareAction.CLINIC, CareAction.of(persistent.recommendHospital, persistent.guidance, persistent.possibleCondition));
        java.util.List<com.sti.tanghap.data.SessionEvent> coughs = java.util.Arrays.asList(
                new com.sti.tanghap.data.SessionEvent(1_000, com.sti.tanghap.data.SessionEvent.Type.COUGH, 80, "Dry cough"),
                new com.sti.tanghap.data.SessionEvent(9_000, com.sti.tanghap.data.SessionEvent.Type.COUGH, 80, "Dry cough"));
        AssessmentEngine.AssessmentResult pneumonia = engine.assess(
                new com.sti.tanghap.ai.BreathingAnalyzer.BreathingResult(30, "", "", "Fast"),
                com.sti.tanghap.ai.CoughEvidence.from(coughs, java.util.Collections.emptyList(),
                        java.util.Collections.emptyList(), 10_000, java.time.ZoneOffset.UTC), null);
        assertEquals(CareAction.HOSPITAL, CareAction.of(pneumonia.recommendHospital, pneumonia.guidance, pneumonia.possibleCondition));
        AssessmentEngine.AssessmentResult none = engine.generatePreliminaryAssessment(
                new com.sti.tanghap.ai.BreathingAnalyzer.BreathingResult(16, "", "", "Normal"),
                null, false, false, false);
        assertEquals(CareAction.MONITOR, CareAction.of(none.recommendHospital, none.guidance, none.possibleCondition));
    }
}
