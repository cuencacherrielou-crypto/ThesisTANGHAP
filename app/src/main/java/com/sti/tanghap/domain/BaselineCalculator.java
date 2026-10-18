package com.sti.tanghap.domain;
import com.sti.tanghap.data.SessionEntity;
import java.util.List;
public class BaselineCalculator {
    public static class Baseline {
        public float avgPeakPressure;
        public float avgInhaleDurationMillis;
        public float avgCoughPerSession;
        public float avgActuationPerSession;
    }
    public Baseline compute(List<SessionEntity> sessions) {
        Baseline b = new Baseline();
        if (sessions == null || sessions.isEmpty()) return b;
        float sumPeak = 0f;
        float sumInhale = 0f;
        float sumCough = 0f;
        float sumAct = 0f;
        for (SessionEntity s : sessions) {
            sumPeak += s.peakPressureKpa;
            sumInhale += s.inhaleDurationMillis;
            sumCough += s.coughCount;
            sumAct += s.actuationCount;
        }
        int n = sessions.size();
        b.avgPeakPressure = sumPeak / n;
        b.avgInhaleDurationMillis = sumInhale / n;
        b.avgCoughPerSession = sumCough / n;
        b.avgActuationPerSession = sumAct / n;
        return b;
    }
}