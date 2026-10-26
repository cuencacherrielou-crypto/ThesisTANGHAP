package com.sti.tanghap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.ai.CoughClass;
import com.sti.tanghap.ai.CoughReview;
import com.sti.tanghap.ai.OtcGuidance;
import com.sti.tanghap.ai.PatientProfile;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.data.SessionEvent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CoughReviewTest {

    // ---------- Review and recalculation ----------

    private static final PatientProfile ADULT = new PatientProfile(30, false, false, false);

    @Test
    public void onlyCountedCoughsAffectCountAndFinding() {
        List<SessionEvent> events = Arrays.asList(
                new SessionEvent(1_000, SessionEvent.Type.COUGH, 80, CoughClass.DRY_COUGH.label),
                new SessionEvent(2_000, SessionEvent.Type.COUGH_IGNORED, 90, CoughClass.SEVERE_COUGH.label),
                new SessionEvent(3_000, SessionEvent.Type.COUGH_REMOVED, 95, CoughClass.WET_COUGH.label));
        CoughReview.Outcome o = CoughReview.evaluate(events, 16, ADULT);
        assertEquals(1, o.coughCount);
        assertEquals(CoughClass.DRY_COUGH, o.finding);
        // Uncounted coughs (older sessions' nearby coughs, removed coughs) must not raise a pneumonia-like possibility
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, o.assessment.possibleCondition);
        assertEquals(OtcGuidance.DRY_COUGH, o.assessment.medicineKey);
        assertEquals("Dry cough (80% confidence)", o.findingText());
    }

    @Test
    public void noCountedCoughs_meansNoCoughFinding() {
        List<SessionEvent> events = Arrays.asList(
                new SessionEvent(1_000, SessionEvent.Type.COUGH_IGNORED, 90, CoughClass.WET_COUGH.label));
        CoughReview.Outcome o = CoughReview.evaluate(events, 16, ADULT);
        assertEquals(0, o.coughCount);
        assertNull(o.finding);
        assertEquals(CoughClass.NON_COUGH.label, o.findingText());
        assertEquals(OtcGuidance.NO_MEDICINE, o.assessment.medicineKey);
    }

    @Test
    public void singleWheezeIsNotedButTheCoughIsTheFinding() {
        List<SessionEvent> events = Arrays.asList(
                new SessionEvent(1_000, SessionEvent.Type.COUGH, 80, CoughClass.DRY_COUGH.label),
                new SessionEvent(2_000, SessionEvent.Type.WHEEZE, 75, CoughClass.WHEEZING.label));
        CoughReview.Outcome o = CoughReview.evaluate(events, 16, ADULT);
        assertEquals(CoughClass.DRY_COUGH, o.finding);
        assertTrue(o.assessment.wheezeDetected);
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, o.assessment.possibleCondition);
    }

    @Test
    public void repeatedWheezeIsTheFinding() {
        List<SessionEvent> events = Arrays.asList(
                new SessionEvent(1_000, SessionEvent.Type.COUGH, 80, CoughClass.DRY_COUGH.label),
                new SessionEvent(2_000, SessionEvent.Type.WHEEZE, 75, CoughClass.WHEEZING.label),
                new SessionEvent(9_000, SessionEvent.Type.WHEEZE, 85, CoughClass.WHEEZING.label));
        CoughReview.Outcome o = CoughReview.evaluate(events, 16, ADULT);
        assertEquals(CoughClass.WHEEZING, o.finding);
        assertEquals("Wheezing (80% confidence)", o.findingText());
        // once in one session, without wheezing on an earlier day, is not asthma
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, o.assessment.possibleCondition);
    }

    @Test
    public void theLastSoundDoesNotDecideTheFinding() {
        // 14:50:45 device session: wet, wet, wet, severe, wet -> wet cough, no severe-cough advice
        List<SessionEvent> events = Arrays.asList(
                new SessionEvent(1_000, SessionEvent.Type.COUGH, 64, CoughClass.WET_COUGH.label),
                new SessionEvent(2_000, SessionEvent.Type.COUGH, 85, CoughClass.WET_COUGH.label),
                new SessionEvent(3_000, SessionEvent.Type.COUGH, 67, CoughClass.WET_COUGH.label),
                new SessionEvent(4_000, SessionEvent.Type.COUGH, 76, CoughClass.SEVERE_COUGH.label),
                new SessionEvent(5_000, SessionEvent.Type.COUGH, 85, CoughClass.WET_COUGH.label));
        CoughReview.Outcome o = CoughReview.evaluate(events, 16, ADULT);
        assertEquals(CoughClass.WET_COUGH, o.finding);
        assertEquals("Wet cough (75% confidence)", o.findingText());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, o.assessment.possibleCondition);
        assertEquals(OtcGuidance.WET_COUGH, o.assessment.medicineKey);
        assertFalse(o.assessment.recommendClinic);
    }

    @Test
    public void removingACough_updatesTheSavedSession() {
        SessionEntity s = new SessionEntity("s");
        s.averageBpm = 16;
        s.synced = true;
        List<SessionEvent> events = new ArrayList<>(Arrays.asList(
                new SessionEvent(1_000, SessionEvent.Type.COUGH, 80, CoughClass.DRY_COUGH.label),
                new SessionEvent(2_000, SessionEvent.Type.COUGH, 90, CoughClass.WET_COUGH.label)));
        CoughReview.applyTo(s, events, ADULT, Collections.emptyList(), Collections.emptyList());
        assertEquals(2, s.coughCount);
        // A tie goes to the type heard last: wet, so no cough suppressant
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, s.possibleCondition);
        assertFalse(s.riskLevelHigh);
        assertFalse(s.otcRecommendation.contains("Dextromethorphan"));

        // The user says the wet cough was someone else's
        events.set(1, events.get(1).withType(SessionEvent.Type.COUGH_REMOVED));
        CoughReview.applyTo(s, events, ADULT, Collections.emptyList(), Collections.emptyList());
        assertEquals(1, s.coughCount);
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, s.possibleCondition);
        assertFalse(s.riskLevelHigh);
        assertTrue(s.otcRecommendation.contains("Dextromethorphan"));
        assertFalse(s.synced); // re-uploaded with the change

        // ...and the change survives the save format
        List<SessionEvent> saved = SessionEvent.decode(s.eventLog);
        assertEquals(SessionEvent.Type.COUGH_REMOVED, saved.get(1).type);
    }

    @Test
    public void olderSessionEventTypes_stillRoundTripAndDescribe() {
        List<SessionEvent> events = Arrays.asList(
                new SessionEvent(1, SessionEvent.Type.COUGH_IGNORED, 88, "Wet cough"),
                new SessionEvent(2, SessionEvent.Type.CALIBRATION, -21.4f, "3"));
        List<SessionEvent> back = SessionEvent.decode(SessionEvent.encode(events));
        assertEquals(SessionEvent.Type.COUGH_IGNORED, back.get(0).type);
        assertTrue(back.get(0).describe().contains("likely someone nearby"));
        assertTrue(back.get(1).describe().contains("3 cough(s)"));
        assertTrue(back.get(0).isCoughEntry());
        assertFalse(back.get(1).isCoughEntry());
    }

    @Test
    public void coughClassFromLabel() {
        for (CoughClass c : CoughClass.values()) assertEquals(c, CoughClass.fromLabel(c.label));
        assertNull(CoughClass.fromLabel("unknown"));
    }
}
