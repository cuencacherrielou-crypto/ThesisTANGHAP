package com.sti.tanghap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.ai.CoughClass;
import com.sti.tanghap.ai.CoughReview;
import com.sti.tanghap.ai.OtcGuidance;
import com.sti.tanghap.ai.PatientProfile;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.data.SessionEvent;
import com.sti.tanghap.domain.CoughHistory;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CoughHistoryTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Manila");
    private static final ZonedDateTime DAY0 = ZonedDateTime.of(2026, 9, 1, 9, 0, 0, 0, ZONE);
    private static final PatientProfile ADULT = new PatientProfile(30, false, false, false);

    private static long at(int day, int minute) {
        return DAY0.plusDays(day).plusMinutes(minute).toInstant().toEpochMilli();
    }

    /** Two coughs (a coughing day) on each of the given days. */
    private static List<Long> coughingOn(int... days) {
        List<Long> t = new ArrayList<>();
        for (int d : days) {
            t.add(at(d, 0));
            t.add(at(d, 1));
        }
        return t;
    }

    // ---------- persistent: coughing on and off for 3 weeks or more ----------

    @Test
    public void coughingOnAndOffFor3Weeks_isPersistent() {
        // coughing days never more than 7 days apart, first to last 21 days
        CoughHistory.Result r = CoughHistory.evaluate(coughingOn(0, 5, 12, 19, 21), at(21, 60), ZONE);
        assertTrue(r.persistent);
        assertEquals(21, r.spellDays);
        assertEquals(5, r.coughingDays);
    }

    @Test
    public void lessThan3Weeks_isNotPersistentYet() {
        CoughHistory.Result r = CoughHistory.evaluate(coughingOn(0, 7, 14, 20), at(20, 60), ZONE);
        assertFalse(r.persistent);
        assertEquals(20, r.spellDays);
    }

    @Test
    public void aCoughFreeWeek_startsANewSpell() {
        // 8 days without coughing between day 6 and day 14
        CoughHistory.Result r = CoughHistory.evaluate(coughingOn(0, 3, 6, 14, 18, 24), at(24, 60), ZONE);
        assertFalse(r.persistent);
        assertEquals(10, r.spellDays); // day 14 .. day 24
    }

    @Test
    public void aSpellThatEndedMoreThanAWeekAgo_isNotPersistentNow() {
        CoughHistory.Result r = CoughHistory.evaluate(coughingOn(0, 7, 14, 21), at(29, 0), ZONE);
        assertFalse(r.persistent);
        assertEquals(0, r.spellDays);
        assertTrue(CoughHistory.evaluate(coughingOn(0, 7, 14, 21), at(28, 0), ZONE).persistent);
    }

    @Test
    public void oneCoughADay_isNotACoughingDay() {
        List<Long> t = new ArrayList<>();
        for (int d = 0; d <= 28; d++) t.add(at(d, 0));
        assertFalse(CoughHistory.evaluate(t, at(28, 60), ZONE).persistent);
    }

    @Test
    public void coughsAfterTheAssessedMoment_areIgnored() {
        assertFalse(CoughHistory.evaluate(coughingOn(0, 7, 14, 21), at(14, 60), ZONE).persistent);
    }

    // ---------- continuous: 3+ episodes in 24 hours, or more than an hour of coughing ----------

    @Test
    public void threeEpisodesIn24Hours_isContinuous() {
        List<Long> t = Arrays.asList(at(0, 0), at(0, 5), at(0, 120), at(0, 300));
        CoughHistory.Result r = CoughHistory.evaluate(t, at(0, 301), ZONE);
        assertEquals(3, r.episodesLast24h); // 0 and 5 min are one episode
        assertTrue(r.continuous);
    }

    @Test
    public void twoEpisodes_isNotContinuous_andOldEpisodesDoNotCount() {
        List<Long> t = Arrays.asList(at(-2, 0), at(0, 0), at(0, 120));
        CoughHistory.Result r = CoughHistory.evaluate(t, at(0, 121), ZONE);
        assertEquals(2, r.episodesLast24h);
        assertFalse(r.continuous);
    }

    @Test
    public void coughingForMoreThanAnHour_isContinuous() {
        List<Long> t = new ArrayList<>();
        for (int m = 0; m <= 60; m += 10) t.add(at(0, m)); // every 10 min for an hour: one episode
        CoughHistory.Result r = CoughHistory.evaluate(t, at(0, 61), ZONE);
        assertEquals(1, r.episodesLast24h);
        assertTrue(r.continuous);
    }

    // ---------- reading saved sessions ----------

    @Test
    public void coughTimesOf_usesCountedCoughsOnly_andOldSessionsTheirCount() {
        SessionEntity s = new SessionEntity("a");
        s.eventLog = SessionEvent.encode(Arrays.asList(
                new SessionEvent(100, SessionEvent.Type.COUGH, 80, CoughClass.DRY_COUGH.label),
                new SessionEvent(200, SessionEvent.Type.COUGH_REMOVED, 80, CoughClass.DRY_COUGH.label),
                new SessionEvent(300, SessionEvent.Type.ACTUATION, 0, "")));
        assertEquals(Collections.singletonList(100L), CoughHistory.coughTimesOf(s));

        SessionEntity old = new SessionEntity("b");
        old.startTimeMillis = 5_000;
        old.coughCount = 3;
        assertEquals(Arrays.asList(5_000L, 5_000L, 5_000L), CoughHistory.coughTimesOf(old));

        old.startTimeMillis = 9_000;
        assertEquals(1, CoughHistory.coughTimesBefore(Arrays.asList(s, old), 9_000).size());
    }

    // ---------- the session's finding ----------

    private static List<SessionEvent> todaysCoughs(CoughClass type) {
        return Arrays.asList(
                new SessionEvent(at(21, 0), SessionEvent.Type.COUGH, 80, type.label),
                new SessionEvent(at(21, 1), SessionEvent.Type.COUGH, 82, type.label));
    }

    @Test
    public void dryCoughAfter3WeeksOfCoughing_isReportedAsPersistent() {
        CoughReview.Outcome o = CoughReview.evaluate(todaysCoughs(CoughClass.DRY_COUGH), 16, ADULT,
                coughingOn(0, 6, 12, 18), Collections.emptyList(), at(21, 30), ZONE);
        assertTrue(o.persistentFromHistory);
        assertEquals(CoughClass.PERSISTENT_COUGH, o.finding);
        assertEquals("Persistent cough (coughing on and off for 21 days)", o.findingText());
        // A dry persistent cough is a doctor's check, not bronchitis (that needs a wet cough too)
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, o.assessment.possibleCondition);
        assertTrue(o.assessment.recommendClinic);
        assertEquals(OtcGuidance.PERSISTENT_COUGH, o.assessment.medicineKey); // see a doctor, no medicine
        assertTrue(o.assessment.observations.contains(AssessmentEngine.PERSISTENT_OBSERVATION));
    }

    @Test
    public void sameCoughWithoutTheHistory_isJustADryCough() {
        CoughReview.Outcome o = CoughReview.evaluate(todaysCoughs(CoughClass.DRY_COUGH), 16, ADULT,
                Collections.emptyList(), Collections.emptyList(), at(21, 30), ZONE);
        assertFalse(o.persistentFromHistory);
        assertEquals(CoughClass.DRY_COUGH, o.finding);
        assertEquals("Dry cough (81% confidence)", o.findingText());   // the dry coughs' average
    }

    @Test
    public void severeCough_staysSevere_evenWithAPersistentHistory() {
        CoughReview.Outcome o = CoughReview.evaluate(todaysCoughs(CoughClass.SEVERE_COUGH), 26, ADULT,
                coughingOn(0, 6, 12, 18), Collections.emptyList(), at(21, 30), ZONE);
        assertFalse(o.persistentFromHistory);
        assertEquals(CoughClass.SEVERE_COUGH, o.finding);
        assertEquals(AssessmentEngine.CATEGORY_PNEUMONIA, o.assessment.possibleCondition);
    }

    @Test
    public void sessionWithoutCoughs_isNotPersistent() {
        CoughReview.Outcome o = CoughReview.evaluate(Collections.emptyList(), 16, ADULT,
                coughingOn(0, 6, 12, 18, 21), Collections.emptyList(), at(21, 30), ZONE);
        assertEquals(0, o.coughCount);
        assertFalse(o.persistentFromHistory);
        assertEquals(CoughClass.NON_COUGH.label, o.findingText());
    }

    @Test
    public void continuousCough_addsAnObservation() {
        List<SessionEvent> events = Arrays.asList(
                new SessionEvent(at(0, 0), SessionEvent.Type.COUGH, 80, CoughClass.DRY_COUGH.label),
                new SessionEvent(at(0, 60), SessionEvent.Type.COUGH, 80, CoughClass.DRY_COUGH.label),
                new SessionEvent(at(0, 120), SessionEvent.Type.COUGH, 80, CoughClass.DRY_COUGH.label));
        CoughReview.Outcome o = CoughReview.evaluate(events, 16, ADULT, Collections.emptyList(),
                Collections.emptyList(), at(0, 121), ZONE);
        assertTrue(o.history.continuous);
        assertTrue(o.assessment.observations.contains(CoughReview.CONTINUOUS_EPISODES_OBSERVATION));
        assertEquals(CoughClass.DRY_COUGH, o.finding); // continuous is noted, not a different cough type
        assertFalse(o.assessment.observations.contains(AssessmentEngine.PERSISTENT_OBSERVATION));
    }
}
