package com.sti.tanghap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.data.SessionEvent;
import com.sti.tanghap.domain.AgeCalculator;
import com.sti.tanghap.domain.AlertEngine;
import com.sti.tanghap.domain.BaselineCalculator;
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

    // ---------- Timeline summaries ----------

    @Test
    public void timeline_listsKeyEventsOnly() {
        List<SessionEvent> events = Arrays.asList(
                // older sessions' air-pressure readings still load but are not listed
                new SessionEvent(10_000, SessionEvent.Type.BREATHING_RATE, 14, ""),
                new SessionEvent(40_000, SessionEvent.Type.INHALATION, 2000, "3.0"),
                // audio gaps are summed up in the data-quality line instead
                new SessionEvent(20_000, SessionEvent.Type.AUDIO_GAP, 120, ""),
                new SessionEvent(30_000, SessionEvent.Type.COUGH, 90, "Dry cough"));
        List<String> lines = SessionTimeline.keyEventLines(events, Locale.US);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("Cough detected: Dry cough (90% confidence)"));
    }

    @Test
    public void dataQuality_sumsTheAudioLostOverBluetooth() {
        List<SessionEvent> events = Arrays.asList(
                new SessionEvent(10_000, SessionEvent.Type.AUDIO_GAP, 120, ""),
                new SessionEvent(20_000, SessionEvent.Type.AUDIO_GAP, 280, ""));
        assertEquals("Data quality: 2 Bluetooth data gaps (about 0.4 s of audio lost and filled in); "
                + "readings may be slightly incomplete.", SessionTimeline.dataQualitySummary(2, events));
        assertNull(SessionTimeline.dataQualitySummary(0, events));
        // older sessions counted lost air-pressure packets, with no audio-gap events
        assertEquals("Data quality: 1 Bluetooth data gap; readings may be slightly incomplete.",
                SessionTimeline.dataQualitySummary(1, new ArrayList<>()));
    }

    @Test
    public void audioGap_roundTripsAndDescribes() {
        List<SessionEvent> back = SessionEvent.decode(SessionEvent.encode(Arrays.asList(
                new SessionEvent(5, SessionEvent.Type.AUDIO_GAP, 250, ""))));
        assertEquals(SessionEvent.Type.AUDIO_GAP, back.get(0).type);
        assertEquals("Audio gap: 250 ms lost over Bluetooth (filled in)", back.get(0).describe());
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

    // ---------- Older sessions with air-pressure data ----------

    @Test
    public void baseline_usesCoughsAndPressesOnly() {
        SessionEntity a = new SessionEntity("a");
        a.peakPressureKpa = 10f;            // older session: still loads, no longer used
        a.inhaleDurationMillis = 2000;
        a.coughCount = 4;
        SessionEntity b = new SessionEntity("b");
        b.actuationCount = 2;
        BaselineCalculator.Baseline base = new BaselineCalculator().compute(Arrays.asList(a, b));
        assertEquals(2f, base.avgCoughPerSession, 0.001f);
        assertEquals(1f, base.avgActuationPerSession, 0.001f);
    }

    @Test
    public void alerts_noneWhenNothingMeasured() {
        List<AlertEngine.Alert> alerts = new AlertEngine().evaluate(
                new BaselineCalculator.Baseline(), -1, 0, 0, 0);
        assertTrue(alerts.isEmpty());
    }
}
