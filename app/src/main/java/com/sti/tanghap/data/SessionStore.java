package com.sti.tanghap.data;

import android.content.Context;
import com.sti.tanghap.domain.BaselineCalculator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Saves finished sessions and builds their baseline comparison. Call off the main thread. */
public final class SessionStore {

    /** The baseline is the user's sessions in the 7 days before a session. */
    public static final long BASELINE_WINDOW_MS = TimeUnit.DAYS.toMillis(7);

    private SessionStore() {}

    /**
     * Saves a new session once: if a session with this id is already stored (for example the
     * save was retried), the stored copy is kept. Then uploads it to Firestore.
     *
     * @return true when the session was stored now
     */
    public static boolean saveNew(Context ctx, SessionEntity s) {
        if (s.userId == null) return false;
        SessionDao dao = AppDatabase.getInstance(ctx).sessionDao();
        if (dao.getByIdForUser(s.id, s.userId) != null) return false;
        s.baselineComparison = baselineText(s, baselineFor(dao, s));
        dao.insert(s);
        new SessionRepository(ctx).uploadSession(s);
        return true;
    }

    /** Baseline = this user's sessions in the 7 days before this session, excluding the session itself. */
    public static BaselineCalculator.Baseline baselineFor(SessionDao dao, SessionEntity session) {
        List<SessionEntity> previous = new ArrayList<>();
        if (session.userId != null) {
            for (SessionEntity s : dao.getSinceForUser(session.userId, session.startTimeMillis - BASELINE_WINDOW_MS)) {
                if (!s.id.equals(session.id) && s.startTimeMillis < session.startTimeMillis) previous.add(s);
            }
        }
        return new BaselineCalculator().compute(previous);
    }

    public static String baselineText(SessionEntity session, BaselineCalculator.Baseline baseline) {
        if (baseline.avgPeakPressure <= 0) {
            return "No sessions with pressure readings in the previous 7 days yet. Establishing baseline...";
        }
        if (session.peakPressureKpa <= 0) {
            return "No inhalation pressure was recorded in this session, so it cannot be compared with your baseline.";
        }
        float diff = ((session.peakPressureKpa - baseline.avgPeakPressure) / baseline.avgPeakPressure) * 100;
        String direction = diff >= 0 ? "stronger" : "weaker";
        return String.format(Locale.getDefault(),
                "Inhalation effort is %.1f%% %s than your 7-day average baseline (%.1f kPa).",
                Math.abs(diff), direction, baseline.avgPeakPressure);
    }
}
