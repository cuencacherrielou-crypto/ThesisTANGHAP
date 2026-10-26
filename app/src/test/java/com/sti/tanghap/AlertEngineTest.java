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
        
        // Added age=25 and bpm=16 (normal for adult) to match new signature
        List<AlertEngine.Alert> alerts = new AlertEngine().evaluate(
                b,
                25,    // userAge
                16,    // todayBpm
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
                b, 25, 25, 0, 0
        );
        boolean found = false;
        for (AlertEngine.Alert a : alerts) {
            if (a.title.equals("Breathing faster than normal")) {
                found = true;
                // the rate is estimated from breathing sounds and never shown as a number
                assertTrue(a.message, !a.message.contains("25") && !a.message.contains("BPM"));
            }
        }
        assertTrue(found);
    }
}
