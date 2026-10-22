package com.sti.tanghap.domain;

import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.ai.SavedAssessment;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.data.SessionEvent;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One row per calendar day, including days with no monitoring, so gaps in tracking are
 * visible. Readings are placed on the day they actually happened using their time stamps,
 * so a session that runs past midnight is split between the two days. Sessions saved before
 * time stamps existed are counted on the day they started.
 */
public final class DailySummary {

    public static final class Day {
        /** Local midnight that starts this day. */
        public final long dayStartMillis;
        public int sessions;
        public long monitoredMillis;
        public int coughs;
        public int wheezes;
        public int inhalerPresses;
        int bpmSum;
        int bpmCount;
        /** Possible conditions from sessions that started this day (never diagnoses). */
        public final Set<String> possibleConditions = new LinkedHashSet<>();

        Day(long dayStartMillis) {
            this.dayStartMillis = dayStartMillis;
        }

        /** Average breathing rate for the day, or 0 when none was measured. */
        public int averageBpm() {
            return bpmCount > 0 ? Math.round(bpmSum / (float) bpmCount) : 0;
        }

        public boolean hasData() {
            return monitoredMillis > 0 || sessions > 0 || coughs > 0 || inhalerPresses > 0;
        }
    }

    private DailySummary() {}

    /**
     * @param sessions any order
     * @param today    a calendar set to the current time, in the phone's time zone
     * @param days     how many days to include, ending with today
     * @return newest day first
     */
    public static List<Day> build(List<SessionEntity> sessions, Calendar today, int days) {
        List<Day> out = new ArrayList<>();
        if (days <= 0) return out;

        // Day boundaries via the calendar, so daylight-saving changes are handled
        Calendar c = (Calendar) today.clone();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long[] starts = new long[days + 1];
        c.add(Calendar.DAY_OF_YEAR, -(days - 1));
        for (int i = 0; i <= days; i++) {
            starts[i] = c.getTimeInMillis();
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        List<Day> oldestFirst = new ArrayList<>();
        for (int i = 0; i < days; i++) oldestFirst.add(new Day(starts[i]));

        for (SessionEntity s : sessions) {
            Day startDay = dayOf(oldestFirst, starts, s.startTimeMillis);
            if (startDay != null) {
                startDay.sessions++;
                String condition = SavedAssessment.of(s, null).possibleCondition;
                if (condition != null && !AssessmentEngine.CATEGORY_STANDARD.equals(condition)) {
                    startDay.possibleConditions.add(condition);
                }
            }

            // Monitored time, split at midnight
            long end = Math.max(s.endTimeMillis, s.startTimeMillis);
            for (int i = 0; i < days; i++) {
                long overlap = Math.min(end, starts[i + 1]) - Math.max(s.startTimeMillis, starts[i]);
                if (overlap > 0) oldestFirst.get(i).monitoredMillis += overlap;
            }

            List<SessionEvent> events = SessionEvent.decode(s.eventLog);
            boolean hasCoughStamps = false;
            boolean hasPressStamps = false;
            boolean hasRateStamps = false;
            for (SessionEvent e : events) {
                switch (e.type) {
                    case COUGH:
                    case COUGH_IGNORED:
                    case COUGH_REMOVED:
                        hasCoughStamps = true;
                        break;
                    case ACTUATION:
                        hasPressStamps = true;
                        break;
                    case BREATHING_RATE:
                        hasRateStamps = true;
                        break;
                    default:
                        break;
                }
                Day d = dayOf(oldestFirst, starts, e.timeMillis);
                if (d == null) continue;
                if (e.type == SessionEvent.Type.COUGH) d.coughs++;
                else if (e.type == SessionEvent.Type.WHEEZE) d.wheezes++;
                else if (e.type == SessionEvent.Type.ACTUATION) d.inhalerPresses++;
                else if (e.type == SessionEvent.Type.BREATHING_RATE) {
                    d.bpmSum += Math.round(e.value);
                    d.bpmCount++;
                }
            }

            // Sessions without time stamps (or with none of a kind): use the session totals
            if (startDay != null) {
                if (!hasCoughStamps) startDay.coughs += s.coughCount;
                if (!hasPressStamps) startDay.inhalerPresses += s.actuationCount;
                if (!hasRateStamps && s.averageBpm > 0) {
                    startDay.bpmSum += s.averageBpm;
                    startDay.bpmCount++;
                }
            }
        }

        for (int i = days - 1; i >= 0; i--) out.add(oldestFirst.get(i));
        return out;
    }

    private static Day dayOf(List<Day> oldestFirst, long[] starts, long timeMillis) {
        for (int i = 0; i < oldestFirst.size(); i++) {
            if (timeMillis >= starts[i] && timeMillis < starts[i + 1]) return oldestFirst.get(i);
        }
        return null;
    }
}
