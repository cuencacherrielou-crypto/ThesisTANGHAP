package com.sti.tanghap.domain;

import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.data.SessionEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Decides from the user's recorded coughs over time whether they have a persistent cough.
 * A single cough cannot show this (on recordings of people with and without a persistent cough
 * the sound model was barely better than guessing), so it is measured the way it is defined
 * clinically, by how long the coughing has gone on:
 * <ul>
 *   <li><b>Persistent cough</b>: coughing on and off for 3 weeks or more. Days with at least
 *   {@link #MIN_COUGHS_PER_DAY} counted coughs are coughing days; they belong to one spell while
 *   no gap between them is longer than {@link #MAX_GAP_DAYS}; the spell must still be going on
 *   (a coughing day in the last {@link #MAX_GAP_DAYS} days) and span at least
 *   {@link #PERSISTENT_DAYS} days.</li>
 *   <li><b>Continuous cough</b> (NHS definition): coughing a lot for more than an hour, or 3 or
 *   more coughing episodes in 24 hours. Coughs less than {@link #EPISODE_GAP_MS} apart are one
 *   episode.</li>
 * </ul>
 */
public final class CoughHistory {

    public static final int MIN_COUGHS_PER_DAY = 2;
    public static final int MAX_GAP_DAYS = 7;
    public static final int PERSISTENT_DAYS = 21;
    public static final long EPISODE_GAP_MS = TimeUnit.MINUTES.toMillis(15);
    public static final int CONTINUOUS_EPISODES = 3;
    public static final long LONG_EPISODE_MS = TimeUnit.HOURS.toMillis(1);
    /** How far back sessions are read; longer than any spell that still counts as one. */
    public static final long LOOKBACK_MS = TimeUnit.DAYS.toMillis(120);

    public static final class Result {
        public final boolean persistent;
        /** Days from the first to the last coughing day of the current spell (0 = none). */
        public final int spellDays;
        public final int coughingDays;
        public final boolean continuous;
        public final int episodesLast24h;

        Result(boolean persistent, int spellDays, int coughingDays, boolean continuous, int episodesLast24h) {
            this.persistent = persistent;
            this.spellDays = spellDays;
            this.coughingDays = coughingDays;
            this.continuous = continuous;
            this.episodesLast24h = episodesLast24h;
        }
    }

    private CoughHistory() {}

    /**
     * @param coughTimes every counted cough (epoch ms), from earlier sessions and this one
     * @param nowMillis  the moment being assessed, e.g. the session's end; later coughs are ignored
     */
    public static Result evaluate(List<Long> coughTimes, long nowMillis, ZoneId zone) {
        List<Long> times = new ArrayList<>();
        for (Long t : coughTimes) if (t != null && t <= nowMillis) times.add(t);
        Collections.sort(times);

        // ---- persistent: the current spell of coughing days
        TreeMap<LocalDate, Integer> perDay = new TreeMap<>();
        for (long t : times) perDay.merge(day(t, zone), 1, Integer::sum);
        List<LocalDate> coughingDays = new ArrayList<>();
        for (java.util.Map.Entry<LocalDate, Integer> e : perDay.entrySet()) {
            if (e.getValue() >= MIN_COUGHS_PER_DAY) coughingDays.add(e.getKey());
        }
        int spellDays = 0;
        int spellCount = 0;
        LocalDate today = day(nowMillis, zone);
        if (!coughingDays.isEmpty()) {
            LocalDate last = coughingDays.get(coughingDays.size() - 1);
            if (ChronoUnit.DAYS.between(last, today) <= MAX_GAP_DAYS) {
                LocalDate first = last;
                spellCount = 1;
                for (int i = coughingDays.size() - 2; i >= 0; i--) {
                    LocalDate d = coughingDays.get(i);
                    if (ChronoUnit.DAYS.between(d, first) > MAX_GAP_DAYS) break;
                    first = d;
                    spellCount++;
                }
                spellDays = (int) ChronoUnit.DAYS.between(first, last);
            }
        }
        boolean persistent = spellDays >= PERSISTENT_DAYS;

        // ---- continuous: episodes in the last 24 hours
        int episodes = 0;
        boolean longEpisode = false;
        long episodeStart = 0, previous = Long.MIN_VALUE;
        for (long t : times) {
            if (t <= nowMillis - TimeUnit.HOURS.toMillis(24)) continue;
            if (previous == Long.MIN_VALUE || t - previous > EPISODE_GAP_MS) {
                episodes++;
                episodeStart = t;
            }
            if (t - episodeStart >= LONG_EPISODE_MS) longEpisode = true;
            previous = t;
        }
        boolean continuous = episodes >= CONTINUOUS_EPISODES || longEpisode;
        return new Result(persistent, spellDays, spellCount, continuous, episodes);
    }

    /** Times of the counted coughs in a saved session's event log. */
    public static List<Long> coughTimesOf(SessionEntity s) {
        List<Long> out = new ArrayList<>();
        for (SessionEvent e : SessionEvent.decode(s.eventLog)) {
            if (e.type == SessionEvent.Type.COUGH) out.add(e.timeMillis);
        }
        // Sessions saved before coughs were time-stamped: their count, at the session start
        if (out.isEmpty()) for (int i = 0; i < s.coughCount; i++) out.add(s.startTimeMillis);
        return out;
    }

    /** Counted coughs of the sessions that started before {@code beforeMillis}. */
    public static List<Long> coughTimesBefore(List<SessionEntity> sessions, long beforeMillis) {
        List<Long> out = new ArrayList<>();
        for (SessionEntity s : sessions) {
            if (s.startTimeMillis < beforeMillis) out.addAll(coughTimesOf(s));
        }
        return out;
    }

    /** Times of the logged wheezes of the sessions that started before {@code beforeMillis}. */
    public static List<Long> wheezeTimesBefore(List<SessionEntity> sessions, long beforeMillis) {
        List<Long> out = new ArrayList<>();
        for (SessionEntity s : sessions) {
            if (s.startTimeMillis >= beforeMillis) continue;
            for (SessionEvent e : SessionEvent.decode(s.eventLog)) {
                if (e.type == SessionEvent.Type.WHEEZE) out.add(e.timeMillis);
            }
        }
        return out;
    }

    private static LocalDate day(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
    }
}
