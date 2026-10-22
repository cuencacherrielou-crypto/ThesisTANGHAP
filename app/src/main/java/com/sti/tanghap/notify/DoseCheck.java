package com.sti.tanghap.notify;

import android.content.Context;

import com.google.firebase.auth.FirebaseAuth;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.ProfilePrefs;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.data.SessionEvent;
import com.sti.tanghap.domain.DoseReminder;

import java.util.Calendar;
import java.util.List;

/** Checks today's recorded inhaler actuations against the prescribed doses. Run off the main thread. */
public final class DoseCheck {

    private DoseCheck() {}

    public static DoseReminder.Result checkToday(Context ctx) {
        int prescribed = ProfilePrefs.prescribedDoses(ctx);
        Calendar cal = Calendar.getInstance();
        int minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        return new DoseReminder().evaluate(prescribed, actuationsToday(ctx), minuteOfDay);
    }

    public static int actuationsToday(Context ctx) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return 0;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        long midnight = cal.getTimeInMillis();
        List<SessionEntity> sessions = AppDatabase.getInstance(ctx).sessionDao()
                .getEndedSinceForUser(uid, midnight);
        int total = 0;
        for (SessionEntity s : sessions) total += actuationsSince(s, midnight);
        return total;
    }

    /**
     * Inhaler presses in this session at or after the given time. Uses the time-stamped
     * presses when the session has them, so a session that runs past midnight is split
     * correctly; older sessions without time stamps count only if they started today.
     */
    public static int actuationsSince(SessionEntity s, long sinceMillis) {
        List<SessionEvent> events = SessionEvent.decode(s.eventLog);
        boolean hasStamps = false;
        int count = 0;
        for (SessionEvent e : events) {
            if (e.type != SessionEvent.Type.ACTUATION) continue;
            hasStamps = true;
            if (e.timeMillis >= sinceMillis) count++;
        }
        if (hasStamps) return count;
        return s.startTimeMillis >= sinceMillis ? s.actuationCount : 0;
    }
}
