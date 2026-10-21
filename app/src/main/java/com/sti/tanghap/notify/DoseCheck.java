package com.sti.tanghap.notify;

import android.content.Context;

import com.google.firebase.auth.FirebaseAuth;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.ProfilePrefs;
import com.sti.tanghap.data.SessionEntity;
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

        List<SessionEntity> today = AppDatabase.getInstance(ctx).sessionDao()
                .getSinceForUser(uid, cal.getTimeInMillis());
        int total = 0;
        for (SessionEntity s : today) total += s.actuationCount;
        return total;
    }
}
