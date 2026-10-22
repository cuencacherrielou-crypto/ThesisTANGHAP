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
import com.sti.tanghap.ai.processor.AudioLevel;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.data.SessionEvent;
import com.sti.tanghap.domain.NearbyCoughFilter;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NearbyCoughFilterTest {

    // ---------- Loudness measurement ----------

    /** 2 s of 16-bit PCM: silence with one 100 ms square burst of the given amplitude. */
    private static byte[] clipWithBurst(int amplitude) {
        byte[] pcm = new byte[32000 * 2];
        for (int i = 16000; i < 17600; i++) {
            short s = (short) (i % 2 == 0 ? amplitude : -amplitude);
            pcm[2 * i] = (byte) (s & 0xFF);
            pcm[2 * i + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return pcm;
    }

    @Test
    public void level_ofFullScaleBurstIsAbout0dBFS() {
        assertEquals(0f, AudioLevel.peakDbfs(clipWithBurst(32767)), 0.1f);
    }

    @Test
    public void level_drops6dBWhenAmplitudeHalves() {
        float loud = AudioLevel.peakDbfs(clipWithBurst(16000));
        float half = AudioLevel.peakDbfs(clipWithBurst(8000));
        assertEquals(6.02f, loud - half, 0.05f);
    }

    @Test
    public void level_measuresTheBurstNotTheWholeClip() {
        // The 100 ms burst decides the level even though 95% of the clip is silent
        assertEquals(AudioLevel.peakDbfs(clipWithBurst(10000)),
                (float) (20 * Math.log10(10000 / 32768.0)), 0.1f);
    }

    @Test
    public void level_silenceAndEmpty() {
        assertEquals(AudioLevel.SILENCE_DBFS, AudioLevel.peakDbfs(new byte[64000]), 0f);
        assertEquals(AudioLevel.SILENCE_DBFS, AudioLevel.peakDbfs(new byte[0]), 0f);
        assertEquals(AudioLevel.SILENCE_DBFS, AudioLevel.peakDbfs(null), 0f);
    }

    // ---------- Filter ----------

    @Test
    public void noCalibration_countsEveryCough() {
        NearbyCoughFilter f = new NearbyCoughFilter(null);
        assertTrue(f.isFromUser(-90f));
    }

    @Test
    public void calibration_usesMedianOfUserCoughs() {
        NearbyCoughFilter f = new NearbyCoughFilter(null);
        f.startCalibration();
        assertFalse(f.addCalibrationCough(-20f));
        assertFalse(f.addCalibrationCough(-40f)); // one unusually soft cough does not decide it
        assertTrue(f.addCalibrationCough(-22f));
        assertTrue(f.finishCalibration());
        assertEquals(-22f, f.getReferenceDb(), 0.001f);
        assertFalse(f.isCalibrating());
    }

    @Test
    public void filter_keepsUserCoughs_dropsQuieterNearbyCoughs() {
        NearbyCoughFilter f = new NearbyCoughFilter(-20f);
        assertTrue(f.isFromUser(-20f));
        assertTrue(f.isFromUser(-29.9f));   // a softer cough from the user still counts
        assertTrue(f.isFromUser(-30f));     // exactly at the 10 dB margin
        assertFalse(f.isFromUser(-30.1f));  // about 3x farther away: someone nearby
        assertTrue(f.isFromUser(-5f));      // louder than calibration still counts
    }

    @Test
    public void calibrationWithNoCough_keepsPreviousLevel() {
        NearbyCoughFilter f = new NearbyCoughFilter(-25f);
        f.startCalibration();
        assertFalse(f.finishCalibration());
        assertEquals(-25f, f.getReferenceDb(), 0.001f);
    }

    @Test
    public void skip_turnsFilterOff() {
        NearbyCoughFilter f = new NearbyCoughFilter(-25f);
        f.disable();
        assertNull(f.getReferenceDb());
        assertTrue(f.isFromUser(-100f));
    }

    @Test
    public void calibrationCoughs_onlyAcceptedWhileCalibrating() {
        NearbyCoughFilter f = new NearbyCoughFilter(null);
        assertFalse(f.addCalibrationCough(-20f));
        assertEquals(0, f.calibrationCoughsHeard());
    }

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
        // The nearby person's severe cough must not raise a pneumonia-like possibility
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
    public void wheezeStillCounts_andIsNotLoudnessFiltered() {
        List<SessionEvent> events = Arrays.asList(
                new SessionEvent(1_000, SessionEvent.Type.COUGH, 80, CoughClass.DRY_COUGH.label),
                new SessionEvent(2_000, SessionEvent.Type.WHEEZE, 75, CoughClass.WHEEZING.label));
        CoughReview.Outcome o = CoughReview.evaluate(events, 16, ADULT);
        assertEquals(CoughClass.WHEEZING, o.finding);
        assertEquals(AssessmentEngine.CATEGORY_ASTHMA, o.assessment.possibleCondition);
    }

    @Test
    public void removingACough_updatesTheSavedSession() {
        SessionEntity s = new SessionEntity("s");
        s.averageBpm = 16;
        s.synced = true;
        List<SessionEvent> events = new ArrayList<>(Arrays.asList(
                new SessionEvent(1_000, SessionEvent.Type.COUGH, 80, CoughClass.DRY_COUGH.label),
                new SessionEvent(2_000, SessionEvent.Type.COUGH, 90, CoughClass.SEVERE_COUGH.label)));
        CoughReview.applyTo(s, events, ADULT);
        assertEquals(2, s.coughCount);
        assertEquals(AssessmentEngine.CATEGORY_PNEUMONIA, s.possibleCondition);
        assertTrue(s.riskLevelHigh);

        // The user says the severe cough was someone else's
        events.set(1, events.get(1).withType(SessionEvent.Type.COUGH_REMOVED));
        CoughReview.applyTo(s, events, ADULT);
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
    public void newEventTypes_roundTripAndDescribe() {
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
