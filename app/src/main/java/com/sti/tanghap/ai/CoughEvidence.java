package com.sti.tanghap.ai;

import com.sti.tanghap.data.SessionEvent;
import com.sti.tanghap.domain.CoughHistory;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The cough and wheeze signs of a session, together with the user's earlier sessions, that the
 * assessment combines. A possible condition is named only when all of its signs are present
 * together (see AssessmentEngine): one cough or wheeze the model misheard, or one wet-sounding
 * cough, is never enough on its own.
 */
public final class CoughEvidence {

    /** A productive (wet) cough: at least this many wet-sounding coughs, and at least half of the coughs. */
    public static final int PRODUCTIVE_MIN_WET = 3;
    /** Wheezing heard repeatedly in the session: this many confirmed wheezes (each already heard twice). */
    public static final int WHEEZE_MIN = 2;
    /** Acute bronchitis: a cough that has lasted more than 5 days (AAFP, Kinkade & Long 2016). */
    public static final int LASTING_COUGH_DAYS = 5;
    /** Wheezing that returns on another day within this time is recurrent (varies over time, GINA). */
    public static final long WHEEZE_RECUR_MS = TimeUnit.DAYS.toMillis(28);
    /** Coughs needed with fast breathing for the pneumonia-like sign, so one misheard sound is not enough. */
    public static final int PNEUMONIA_MIN_COUGHS = 2;

    private final int[] count = new int[CoughClass.values().length];
    private final int[] percentSum = new int[CoughClass.values().length];
    private final int[] lastIndex = new int[CoughClass.values().length];
    private int index;

    /** Counted coughs of any type. */
    public int coughs;
    /** Confirmed wheezes logged in this session. */
    public int wheezes;
    /** Coughing on and off for 3 weeks or more (CoughHistory). */
    public boolean persistent;
    /** A cough lasting 2 weeks or more with coughing fits (CoughHistory, CDC criteria). */
    public boolean severe;
    /** Coughing a lot for an hour, or 3 or more episodes in 24 hours (CoughHistory). */
    public boolean continuous;
    /** Days from the first to the last coughing day of the current spell (CoughHistory). */
    public int coughSpellDays;
    /** Confirmed wheezing in an earlier session on another day, within {@link #WHEEZE_RECUR_MS}. */
    public boolean wheezeOnEarlierDay;
    /** The cough history the flags above come from; null for {@link #single}. */
    public CoughHistory.Result history;

    public CoughEvidence() {}

    /** One sound and no history, e.g. the live screen before a session has any events. */
    public static CoughEvidence single(CoughClass c) {
        CoughEvidence e = new CoughEvidence();
        if (c == null) return e;
        if (c == CoughClass.PERSISTENT_COUGH || c == CoughClass.SEVERE_COUGH) {
            // both stand for a cough history, not for one sound
            e.persistent = c == CoughClass.PERSISTENT_COUGH;
            e.severe = c == CoughClass.SEVERE_COUGH;
            e.add(CoughClass.DRY_COUGH, 0);
        } else if (c != CoughClass.NON_COUGH) {
            e.add(c, 0);
        }
        return e;
    }

    /**
     * @param events           the session's events; only counted coughs (type COUGH) and logged wheezes are used
     * @param priorCoughTimes  counted coughs of the user's earlier sessions (epoch ms)
     * @param priorWheezeTimes logged wheezes of the user's earlier sessions (epoch ms)
     * @param nowMillis        the moment assessed, e.g. the session's end
     */
    public static CoughEvidence from(List<SessionEvent> events, List<Long> priorCoughTimes,
                                     List<Long> priorWheezeTimes, long nowMillis, ZoneId zone) {
        CoughEvidence e = new CoughEvidence();
        List<Long> coughTimes = new ArrayList<>(priorCoughTimes);
        for (SessionEvent ev : events) {
            if (ev.type == SessionEvent.Type.COUGH) {
                CoughClass c = CoughClass.fromLabel(ev.label);
                e.add(c != null && c.isCough ? c : CoughClass.DRY_COUGH, Math.round(ev.value));
                coughTimes.add(ev.timeMillis);
            } else if (ev.type == SessionEvent.Type.WHEEZE) {
                e.add(CoughClass.WHEEZING, Math.round(ev.value));
            }
        }
        e.history = CoughHistory.evaluate(coughTimes, nowMillis, zone);
        if (e.coughs > 0) {   // earlier coughing alone says nothing about this session
            e.persistent = e.history.persistent;
            e.severe = e.history.severe;
            e.continuous = e.history.continuous;
            e.coughSpellDays = e.history.spellDays;
        }
        java.time.LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        for (Long t : priorWheezeTimes) {
            if (t == null || t > nowMillis || nowMillis - t > WHEEZE_RECUR_MS) continue;
            if (!Instant.ofEpochMilli(t).atZone(zone).toLocalDate().equals(today)) e.wheezeOnEarlierDay = true;
        }
        return e;
    }

    private void add(CoughClass c, int percent) {
        count[c.ordinal()]++;
        percentSum[c.ordinal()] += percent;
        lastIndex[c.ordinal()] = ++index;
        if (c.isCough) coughs++;
        else if (c == CoughClass.WHEEZING) wheezes++;
    }

    public int count(CoughClass c) {
        return count[c.ordinal()];
    }

    /** Average logged confidence (%) of the sounds of this class. */
    public int meanPercent(CoughClass c) {
        int n = count[c.ordinal()];
        return n == 0 ? 0 : Math.round(percentSum[c.ordinal()] / (float) n);
    }

    /**
     * The most frequent cough type, dry or wet; a tie goes to the type heard last. Null without dry
     * or wet coughs (coughs saved as "severe" by older builds have no dry / wet type).
     */
    public CoughClass mainCoughType() {
        CoughClass best = null;
        for (CoughClass c : new CoughClass[]{CoughClass.DRY_COUGH, CoughClass.WET_COUGH}) {
            if (count(c) == 0) continue;
            if (best == null || count(c) > count(best)
                    || (count(c) == count(best) && lastIndex[c.ordinal()] > lastIndex[best.ordinal()])) {
                best = c;
            }
        }
        return best;
    }

    /** Wet-sounding coughs are many and at least half of the coughs: a productive cough. */
    public boolean productive() {
        int wet = count(CoughClass.WET_COUGH);
        return wet >= PRODUCTIVE_MIN_WET && 2 * wet >= coughs;
    }

    public boolean repeatedWheeze() {
        return wheezes >= WHEEZE_MIN;
    }

    /** The current coughing spell has lasted more than {@link #LASTING_COUGH_DAYS} days (or is persistent). */
    public boolean lastingCough() {
        return persistent || coughSpellDays >= LASTING_COUGH_DAYS;
    }
}
