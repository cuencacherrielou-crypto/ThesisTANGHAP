package com.sti.tanghap.domain;
import com.sti.tanghap.data.SessionEntity;
import java.util.List;
public class BaselineCalculator {
    public static class Baseline {
        public float avgPeakPressure;
        public float avgInhaleDurationMillis;
        public float avgCoughPerSession;
        public float avgActuationPerSession;
        /** Sessions averaged (0 = no baseline yet). */
        public int sessions;
    }
    /**
     * Averages over the given sessions. Peak pressure and inhalation duration only count
     * sessions where they were measured (non-zero), so a session without pressure data does
     * not pull the baseline down; cough and actuation counts include every session.
     */
    public Baseline compute(List<SessionEntity> sessions) {
        Baseline b = new Baseline();
        if (sessions == null || sessions.isEmpty()) return b;
        float sumPeak = 0f;
        int peakCount = 0;
        float sumInhale = 0f;
        int inhaleCount = 0;
        float sumCough = 0f;
        float sumAct = 0f;
        for (SessionEntity s : sessions) {
            if (s.peakPressureKpa > 0) {
                sumPeak += s.peakPressureKpa;
                peakCount++;
            }
            if (s.inhaleDurationMillis > 0) {
                sumInhale += s.inhaleDurationMillis;
                inhaleCount++;
            }
            sumCough += s.coughCount;
            sumAct += s.actuationCount;
        }
        int n = sessions.size();
        b.sessions = n;
        b.avgPeakPressure = peakCount > 0 ? sumPeak / peakCount : 0f;
        b.avgInhaleDurationMillis = inhaleCount > 0 ? sumInhale / inhaleCount : 0f;
        b.avgCoughPerSession = sumCough / n;
        b.avgActuationPerSession = sumAct / n;
        return b;
    }
}