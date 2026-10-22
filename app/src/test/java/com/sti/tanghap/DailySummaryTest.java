package com.sti.tanghap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.data.SessionEvent;
import com.sti.tanghap.domain.DailySummary;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

public class DailySummaryTest {

    private static final TimeZone MANILA = TimeZone.getTimeZone("Asia/Manila");

    /** Oct 22 2026, 15:00 Manila time. */
    private static Calendar today() {
        Calendar c = Calendar.getInstance(MANILA);
        c.clear();
        c.set(2026, Calendar.OCTOBER, 22, 15, 0, 0);
        return c;
    }

    private static long at(int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(MANILA);
        c.clear();
        c.set(2026, Calendar.OCTOBER, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void includesEveryDay_evenWithoutMonitoring() {
        List<DailySummary.Day> days = DailySummary.build(Collections.emptyList(), today(), 7);
        assertEquals(7, days.size());
        assertEquals(at(22, 0, 0), days.get(0).dayStartMillis); // newest first
        assertEquals(at(16, 0, 0), days.get(6).dayStartMillis);
        for (DailySummary.Day d : days) assertFalse(d.hasData());
    }

    @Test
    public void sessionAcrossMidnight_isSplitByTimeStamps() {
        SessionEntity s = new SessionEntity("night");
        s.startTimeMillis = at(21, 23, 50);
        s.endTimeMillis = at(22, 0, 10);
        s.coughCount = 2;
        s.actuationCount = 2;
        s.eventLog = SessionEvent.encode(Arrays.asList(
                new SessionEvent(at(21, 23, 55), SessionEvent.Type.COUGH, 80, "Dry cough"),
                new SessionEvent(at(21, 23, 56), SessionEvent.Type.ACTUATION, 1, ""),
                new SessionEvent(at(21, 23, 58), SessionEvent.Type.BREATHING_RATE, 14, ""),
                new SessionEvent(at(22, 0, 5), SessionEvent.Type.COUGH, 85, "Wet cough"),
                new SessionEvent(at(22, 0, 6), SessionEvent.Type.ACTUATION, 1, ""),
                new SessionEvent(at(22, 0, 7), SessionEvent.Type.BREATHING_RATE, 18, "")));

        List<DailySummary.Day> days = DailySummary.build(Collections.singletonList(s), today(), 2);
        DailySummary.Day oct22 = days.get(0);
        DailySummary.Day oct21 = days.get(1);

        assertEquals(1, oct21.sessions);          // counted on the day it started
        assertEquals(0, oct22.sessions);
        assertEquals(10 * 60_000L, oct21.monitoredMillis);
        assertEquals(10 * 60_000L, oct22.monitoredMillis);
        assertEquals(1, oct21.coughs);
        assertEquals(1, oct22.coughs);
        assertEquals(1, oct21.inhalerPresses);
        assertEquals(1, oct22.inhalerPresses);
        assertEquals(14, oct21.averageBpm());
        assertEquals(18, oct22.averageBpm());
        assertTrue(oct22.hasData());
    }

    @Test
    public void removedAndNearbyCoughs_areNotCounted() {
        SessionEntity s = new SessionEntity("s");
        s.startTimeMillis = at(22, 9, 0);
        s.endTimeMillis = at(22, 9, 10);
        s.coughCount = 1;
        s.eventLog = SessionEvent.encode(Arrays.asList(
                new SessionEvent(at(22, 9, 1), SessionEvent.Type.COUGH, 80, "Dry cough"),
                new SessionEvent(at(22, 9, 2), SessionEvent.Type.COUGH_IGNORED, 90, "Wet cough"),
                new SessionEvent(at(22, 9, 3), SessionEvent.Type.COUGH_REMOVED, 90, "Wet cough"),
                new SessionEvent(at(22, 9, 4), SessionEvent.Type.WHEEZE, 70, "Wheezing")));
        DailySummary.Day d = DailySummary.build(Collections.singletonList(s), today(), 1).get(0);
        assertEquals(1, d.coughs);
        assertEquals(1, d.wheezes);
    }

    @Test
    public void olderSessionsWithoutTimeStamps_useSessionTotals() {
        SessionEntity old = new SessionEntity("old");
        old.startTimeMillis = at(20, 8, 0);
        old.endTimeMillis = at(20, 8, 5);
        old.coughCount = 3;
        old.actuationCount = 2;
        old.averageBpm = 17;
        old.otcRecommendation = "Pneumonia Symptoms Indication"; // saved by an older version
        DailySummary.Day d = DailySummary.build(Collections.singletonList(old), today(), 3).get(2);
        assertEquals(3, d.coughs);
        assertEquals(2, d.inhalerPresses);
        assertEquals(17, d.averageBpm());
        assertTrue(d.possibleConditions.contains(AssessmentEngine.CATEGORY_PNEUMONIA));
    }

    @Test
    public void sessionsOutsideTheRange_areIgnored() {
        SessionEntity s = new SessionEntity("long ago");
        s.startTimeMillis = at(1, 8, 0);
        s.endTimeMillis = at(1, 8, 5);
        s.coughCount = 5;
        for (DailySummary.Day d : DailySummary.build(Collections.singletonList(s), today(), 7)) {
            assertFalse(d.hasData());
        }
    }
}
