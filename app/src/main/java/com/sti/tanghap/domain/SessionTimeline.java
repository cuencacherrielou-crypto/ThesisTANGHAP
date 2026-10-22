package com.sti.tanghap.domain;

import com.sti.tanghap.data.SessionEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Text summaries of a session's time-stamped event log, for the result screen and the PDF. */
public final class SessionTimeline {

    private SessionTimeline() {}

    /** Events worth listing one by one (not the frequent breathing-rate readings or inhalations). */
    public static List<SessionEvent> keyEvents(List<SessionEvent> events) {
        List<SessionEvent> out = new ArrayList<>();
        for (SessionEvent e : events) {
            if (e.type != SessionEvent.Type.BREATHING_RATE && e.type != SessionEvent.Type.INHALATION) {
                out.add(e);
            }
        }
        return out;
    }

    public static List<SessionEvent> ofType(List<SessionEvent> events, SessionEvent.Type type) {
        List<SessionEvent> out = new ArrayList<>();
        for (SessionEvent e : events) {
            if (e.type == type) out.add(e);
        }
        return out;
    }

    /** One line per key event, e.g. "10:42:07 AM  Cough detected: Wet cough (87% confidence)". */
    public static List<String> keyEventLines(List<SessionEvent> events, Locale locale) {
        SimpleDateFormat time = new SimpleDateFormat("hh:mm:ss a", locale);
        List<String> lines = new ArrayList<>();
        for (SessionEvent e : keyEvents(events)) {
            lines.add(time.format(new Date(e.timeMillis)) + "  " + e.describe());
        }
        return lines;
    }

    /** e.g. "24 readings, lowest 14 BPM, highest 22 BPM"; null when there are none. */
    public static String breathingRateSummary(List<SessionEvent> events) {
        List<SessionEvent> rates = ofType(events, SessionEvent.Type.BREATHING_RATE);
        if (rates.isEmpty()) return null;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (SessionEvent e : rates) {
            int bpm = Math.round(e.value);
            min = Math.min(min, bpm);
            max = Math.max(max, bpm);
        }
        return rates.size() + (rates.size() == 1 ? " reading" : " readings")
                + ", lowest " + min + " BPM, highest " + max + " BPM";
    }

    /** e.g. "12 inhalations, average 1.8 s, longest 2.4 s"; null when there are none. */
    public static String inhalationSummary(List<SessionEvent> events) {
        List<SessionEvent> inhalations = ofType(events, SessionEvent.Type.INHALATION);
        if (inhalations.isEmpty()) return null;
        float total = 0f;
        float longest = 0f;
        for (SessionEvent e : inhalations) {
            total += e.value;
            longest = Math.max(longest, e.value);
        }
        return String.format(Locale.US, "%d %s, average %.1f s, longest %.1f s",
                inhalations.size(), inhalations.size() == 1 ? "inhalation" : "inhalations",
                total / inhalations.size() / 1000f, longest / 1000f);
    }

    /**
     * Average breathing rate for each minute of the session that has readings.
     * Each row is {minuteStartMillis, averageBpm}.
     */
    public static List<long[]> breathingRatePerMinute(List<SessionEvent> events, long sessionStartMillis) {
        List<long[]> rows = new ArrayList<>();
        long currentMinute = -1;
        int sum = 0;
        int count = 0;
        for (SessionEvent e : ofType(events, SessionEvent.Type.BREATHING_RATE)) {
            long minute = Math.max(0, (e.timeMillis - sessionStartMillis) / 60_000);
            if (minute != currentMinute && count > 0) {
                rows.add(new long[]{sessionStartMillis + currentMinute * 60_000, Math.round(sum / (float) count)});
                sum = 0;
                count = 0;
            }
            currentMinute = minute;
            sum += Math.round(e.value);
            count++;
        }
        if (count > 0) rows.add(new long[]{sessionStartMillis + currentMinute * 60_000, Math.round(sum / (float) count)});
        return rows;
    }
}
