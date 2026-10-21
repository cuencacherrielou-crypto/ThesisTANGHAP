package com.sti.tanghap;

import com.sti.tanghap.domain.AlertEngine;
import com.sti.tanghap.domain.BaselineCalculator;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class AlertEngineTest {
    @Test
    public void coughSpikeTriggersWarning() {
        BaselineCalculator.Baseline b = new BaselineCalculator.Baseline();
        b.avgCoughPerSession = 2f;
        b.avgActuationPerSession = 1f;
        b.avgInhaleDurationMillis = 3000f;
        b.avgPeakPressure = 15f;
        
        // Added age=25 and bpm=16 (normal for adult) to match new signature
        List<AlertEngine.Alert> alerts = new AlertEngine().evaluate(
                b,
                25,    // userAge
                16,    // todayBpm
                15f,   // todayPeakPressure
                3000f, // todayInhaleDurationMillis
                10,    // todayCoughCount
                1      // todayActuationCount
        );
        
        boolean found = false;
        for (AlertEngine.Alert a : alerts) {
            if (a.title.toLowerCase().contains("cough")) found = true;
        }
        assertTrue(found);
    }

    @Test
    public void abnormalBpmTriggersWarning() {
        BaselineCalculator.Baseline b = new BaselineCalculator.Baseline();
        // 25 year old adult, normal is 12-20. 25 is high.
        List<AlertEngine.Alert> alerts = new AlertEngine().evaluate(
                b, 25, 25, 15f, 3000f, 0, 0
        );
        boolean found = false;
        for (AlertEngine.Alert a : alerts) {
            if (a.title.toLowerCase().contains("breathing rate")) found = true;
        }
        assertTrue(found);
    }
}
