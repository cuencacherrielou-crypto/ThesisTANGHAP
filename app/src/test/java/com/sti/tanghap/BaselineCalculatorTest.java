package com.sti.tanghap;
import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.domain.BaselineCalculator;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
public class BaselineCalculatorTest {
    @Test
    public void baselineAveragesWork() {
        List<SessionEntity> list = new ArrayList<>();
        SessionEntity s1 = new SessionEntity("1");
        s1.peakPressureKpa = 10f;
        s1.inhaleDurationMillis = 2000;
        s1.coughCount = 2;
        s1.actuationCount = 1;
        SessionEntity s2 = new SessionEntity("2");
        s2.peakPressureKpa = 20f;
        s2.inhaleDurationMillis = 4000;
        s2.coughCount = 0;
        s2.actuationCount = 3;
        list.add(s1);
        list.add(s2);
        BaselineCalculator.Baseline b = new BaselineCalculator().compute(list);
        assertEquals(15f, b.avgPeakPressure, 0.001);
        assertEquals(3000f, b.avgInhaleDurationMillis, 0.001);
        assertEquals(1f, b.avgCoughPerSession, 0.001);
        assertEquals(2f, b.avgActuationPerSession, 0.001);
    }
}