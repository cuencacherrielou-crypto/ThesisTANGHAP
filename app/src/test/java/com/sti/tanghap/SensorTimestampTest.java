package com.sti.tanghap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.data.SessionEvent;
import com.sti.tanghap.domain.AgeCalculator;
import com.sti.tanghap.domain.AlertEngine;
import com.sti.tanghap.domain.BaselineCalculator;
import com.sti.tanghap.domain.InhalationTracker;
import com.sti.tanghap.domain.SessionTimeline;
import com.sti.tanghap.notify.DoseCheck;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SensorTimestampTest {

    // ---------- Event log ----------

    @Test
    public void eventLog_roundTripsThroughRoomText() {
        List<SessionEvent> events = Arrays.asList(
                new SessionEvent(1_000L, SessionEvent.Type.COUGH, 87f, "Wet cough"),
                new SessionEvent(2_000L, SessionEvent.Type.ACTUATION, 1f, ""),
                new SessionEvent(3_000L, SessionEvent.Type.BREATHING_RATE, 18f, "tab\there"));
        List<SessionEvent> back = SessionEvent.decode(SessionEvent.encode(events));
        assertEquals(3, back.size());
        assertEquals(1_000L, back.get(0).timeMillis);
        assertEquals(SessionEvent.Type.COUGH, back.get(0).type);
        assertEquals(87f, back.get(0).value, 0.001f);
        assertEquals("Wet cough", back.get(0).label);
        assertEquals("tab here", back.get(2).label);
    }

    @Test
    public void eventLog_roundTripsThroughFirestoreMaps() {
        List<SessionEvent> events = Arrays.asList(new SessionEvent(5_000L, SessionEvent.Type.WHEEZE, 70f, "Wheezing"));
        List<Map<String, Object>> maps = SessionEvent.toMaps(events);
        List<SessionEvent> back = SessionEvent.fromMaps(maps);
        assertEquals(1, back.size());
        assertEquals(5_000L, back.get(0).timeMillis);
        assertEquals(SessionEvent.Type.WHEEZE, back.get(0).type);
    }

    @Test
    public void eventLog_skipsBadLinesAndHandlesEmpty() {
        assertTrue(SessionEvent.decode(null).isEmpty());
        assertNull(SessionEvent.encode(new ArrayList<>()));
        List<SessionEvent> back = SessionEvent.decode("garbage\n10\tACTUATION\t1\t\n20\tUNKNOWN\t1\t");
        assertEquals(1, back.size());
        assertEquals(10L, back.get(0).timeMillis);
    }

    // ---------- Inhalation start times ----------

    @Test
    public void inhalation_hasEstimatedStartTime() {
        InhalationTracker t = new InhalationTracker();
        // 10 samples per packet, one packet every 100 ms -> 10 ms per sample
        float[] quiet = new float[10];
        float[] inhale = new float[10];
        for (int i = 0; i < inhale.length; i++) inhale[i] = (i % 2 == 0) ? -3f : -2.9f;
        long time = 1_000;
        t.addSamples(quiet, time);          // t=1000
        t.addSamples(quiet, time += 100);   // t=1100
        t.addSamples(inhale, time += 100);  // t=1200: inhalation starts at the packet's first sample
        t.addSamples(inhale, time += 100);
        t.addSamples(quiet, time += 100);
        t.finish();

        List<InhalationTracker.Inhalation> list = t.getInhalations();
        assertEquals(1, list.size());
        // First sample of the packet that arrived at 1200 was sampled 9 x 10 ms earlier
        assertEquals(1110L, list.get(0).startMillis);
        assertEquals(200L, list.get(0).durationMillis);
        assertEquals(3f, list.get(0).peakKpa, 0.001f);
    }

    // ---------- Timeline summaries ----------

    @Test
    public void timeline_summariesAndPerMinuteAverages() {
        long start = 0;
        List<SessionEvent> events = Arrays.asList(
                new SessionEvent(10_000, SessionEvent.Type.BREATHING_RATE, 14, ""),
                new SessionEvent(20_000, SessionEvent.Type.BREATHING_RATE, 16, ""),
                new SessionEvent(70_000, SessionEvent.Type.BREATHING_RATE, 22, ""),
                new SessionEvent(30_000, SessionEvent.Type.COUGH, 90, "Dry cough"),
                new SessionEvent(40_000, SessionEvent.Type.INHALATION, 2000, "3.0"));
        assertEquals("3 readings, lowest 14 BPM, highest 22 BPM", SessionTimeline.breathingRateSummary(events));
        assertEquals("1 inhalation, average 2.0 s, longest 2.0 s", SessionTimeline.inhalationSummary(events));

        List<long[]> perMinute = SessionTimeline.breathingRatePerMinute(events, start);
        assertEquals(2, perMinute.size());
        assertEquals(0L, perMinute.get(0)[0]);
        assertEquals(15L, perMinute.get(0)[1]);
        assertEquals(60_000L, perMinute.get(1)[0]);
        assertEquals(22L, perMinute.get(1)[1]);

        List<String> lines = SessionTimeline.keyEventLines(events, Locale.US);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("Cough detected: Dry cough (90% confidence)"));
    }

    // ---------- Doses counted by time stamp ----------

    @Test
    public void doses_countOnlyPressesAfterMidnight() {
        long midnight = 1_000_000L;
        SessionEntity s = new SessionEntity("x");
        s.startTimeMillis = midnight - 60_000; // started before midnight
        s.actuationCount = 3;
        s.eventLog = SessionEvent.encode(Arrays.asList(
                new SessionEvent(midnight - 30_000, SessionEvent.Type.ACTUATION, 1, ""),
                new SessionEvent(midnight + 10_000, SessionEvent.Type.ACTUATION, 1, ""),
                new SessionEvent(midnight + 20_000, SessionEvent.Type.ACTUATION, 1, "")));
        assertEquals(2, DoseCheck.actuationsSince(s, midnight));

        SessionEntity old = new SessionEntity("old"); // no time stamps
        old.startTimeMillis = midnight + 5;
        old.actuationCount = 4;
        assertEquals(4, DoseCheck.actuationsSince(old, midnight));
    }

    // ---------- Age from birthday ----------

    @Test
    public void age_fromBirthday_beforeAndAfterBirthday() {
        Calendar today = Calendar.getInstance();
        today.set(2026, Calendar.OCTOBER, 22);
        assertEquals(20, AgeCalculator.fromBirthday("10/22/2006", today));
        assertEquals(19, AgeCalculator.fromBirthday("10/23/2006", today));
        assertEquals(AgeCalculator.UNKNOWN, AgeCalculator.fromBirthday("N/A", today));
        // Birthday wins over a stale stored age; the age field is the fallback
        assertEquals(20, AgeCalculator.resolve("10/22/2006", "18", today));
        assertEquals(18, AgeCalculator.resolve("N/A", "18", today));
        assertEquals(AgeCalculator.UNKNOWN, AgeCalculator.resolve("N/A", "N/A", today));
    }

    // ---------- Sessions without pressure data ----------

    @Test
    public void baseline_ignoresSessionsWithoutPressure() {
        SessionEntity a = new SessionEntity("a");
        a.peakPressureKpa = 10f;
        a.inhaleDurationMillis = 2000;
        SessionEntity b = new SessionEntity("b"); // nothing measured
        BaselineCalculator.Baseline base = new BaselineCalculator().compute(Arrays.asList(a, b));
        assertEquals(10f, base.avgPeakPressure, 0.001f);
        assertEquals(2000f, base.avgInhaleDurationMillis, 0.001f);
    }

    @Test
    public void alerts_noPressureAlertWhenNothingMeasured() {
        BaselineCalculator.Baseline b = new BaselineCalculator.Baseline();
        b.avgPeakPressure = 10f;
        b.avgInhaleDurationMillis = 2000f;
        List<AlertEngine.Alert> alerts = new AlertEngine().evaluate(b, -1, 0, 0f, 0f, 0, 0);
        assertTrue(alerts.isEmpty());
    }
}
