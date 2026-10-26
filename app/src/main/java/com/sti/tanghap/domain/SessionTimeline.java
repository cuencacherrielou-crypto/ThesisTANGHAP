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

    /**
     * Events worth listing one by one: not the frequent audio gaps (summed up in
     * {@link #dataQualitySummary}) or older sessions' breathing-rate readings and inhalations.
     */
    public static List<SessionEvent> keyEvents(List<SessionEvent> events) {
        List<SessionEvent> out = new ArrayList<>();
        for (SessionEvent e : events) {
            if (isKeyEvent(e)) out.add(e);
        }
        return out;
    }

    public static boolean isKeyEvent(SessionEvent e) {
        return e.type != SessionEvent.Type.BREATHING_RATE && e.type != SessionEvent.Type.INHALATION
                && e.type != SessionEvent.Type.AUDIO_GAP;
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

    /**
     * Data-quality line for a session with Bluetooth data gaps, e.g. "Data quality: 3 Bluetooth data
     * gaps (about 0.4 s of audio lost and filled in); readings may be slightly incomplete."
     * Null when there were none. Older sessions counted lost air-pressure packets instead.
     */
    public static String dataQualitySummary(int dataGaps, List<SessionEvent> events) {
        if (dataGaps <= 0) return null;
        float lostMs = 0f;
        for (SessionEvent e : ofType(events, SessionEvent.Type.AUDIO_GAP)) lostMs += e.value;
        return "Data quality: " + dataGaps + (dataGaps == 1 ? " Bluetooth data gap" : " Bluetooth data gaps")
                + (lostMs > 0 ? String.format(Locale.US, " (about %.1f s of audio lost and filled in)", lostMs / 1000f) : "")
                + "; readings may be slightly incomplete.";
    }
}
