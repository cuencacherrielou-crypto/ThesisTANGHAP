package com.sti.tanghap.domain;
import com.sti.tanghap.data.SessionEntity;
import java.util.List;
public class BaselineCalculator {
    public static class Baseline {
        public float avgCoughPerSession;
        public float avgActuationPerSession;
        /** Sessions averaged (0 = no baseline yet). */
        public int sessions;
    }
    /** Average cough and inhaler-press counts per session over the given sessions. */
    public Baseline compute(List<SessionEntity> sessions) {
        Baseline b = new Baseline();
        if (sessions == null || sessions.isEmpty()) return b;
        float sumCough = 0f;
        float sumAct = 0f;
        for (SessionEntity s : sessions) {
            sumCough += s.coughCount;
            sumAct += s.actuationCount;
        }
        int n = sessions.size();
        b.sessions = n;
        b.avgCoughPerSession = sumCough / n;
        b.avgActuationPerSession = sumAct / n;
        return b;
    }
}