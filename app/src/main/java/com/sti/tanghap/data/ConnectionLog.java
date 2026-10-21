package com.sti.tanghap.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Keeps the times of recent unexpected BLE disconnections so the Alerts screen can list them. */
public final class ConnectionLog {

    private static final String PREFS = "TanghapConnectionLog";
    private static final String KEY = "disconnect_times";
    private static final int MAX_ENTRIES = 20;

    private ConnectionLog() {}

    public static void recordDisconnect(Context ctx, long timeMillis) {
        List<Long> times = all(ctx);
        times.add(0, timeMillis);
        while (times.size() > MAX_ENTRIES) times.remove(times.size() - 1);

        StringBuilder sb = new StringBuilder();
        for (Long t : times) {
            if (sb.length() > 0) sb.append(',');
            sb.append(t);
        }
        prefs(ctx).edit().putString(KEY, sb.toString()).apply();
    }

    /** Disconnections newer than the given time, newest first. */
    public static List<Long> since(Context ctx, long sinceMillis) {
        List<Long> out = new ArrayList<>();
        for (Long t : all(ctx)) {
            if (t >= sinceMillis) out.add(t);
        }
        return out;
    }

    private static List<Long> all(Context ctx) {
        List<Long> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(",")) {
            try {
                out.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // skip a damaged entry
            }
        }
        return out;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
