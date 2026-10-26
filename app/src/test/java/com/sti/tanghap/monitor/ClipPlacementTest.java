package com.sti.tanghap.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.sti.tanghap.ai.processor.CoughDetector;
import com.sti.tanghap.ai.processor.MfccExtractor;

import org.junit.Test;

public class ClipPlacementTest {

    private static final int SR = MfccExtractor.SAMPLE_RATE;
    private static final int CLIP = MfccExtractor.CLIP_SAMPLES;

    private static CoughDetector.Cough sound(double startSec, double endSec, double peakSec) {
        return new CoughDetector.Cough(Math.round(startSec * SR), Math.round(endSec * SR),
                Math.round(peakSec * SR), -25f, -50f);
    }

    @Test
    public void clipEndsQuarterSecondAfterTheSound_soItIsClassifiedRightAway() {
        CoughDetector.Cough c = sound(10.0, 10.4, 10.1);
        long start = MonitoringSession.clipStartFor(c);
        assertEquals(c.endSample + SR / 4, start + CLIP);
        // the whole sound is inside the clip
        assertTrue(start <= c.startSample && c.endSample <= start + CLIP);
    }

    @Test
    public void longestSoundThatFits_isStillWhollyInside() {
        CoughDetector.Cough c = sound(10.0, 11.75, 11.0);
        long start = MonitoringSession.clipStartFor(c);
        assertEquals(c.startSample, start);
        assertEquals(c.endSample + SR / 4, start + CLIP);
    }

    @Test
    public void soundTooLongToFit_isCentredOnItsLoudestMoment() {
        CoughDetector.Cough c = sound(10.0, 12.5, 11.2);
        assertEquals(c.peakSample - CLIP / 2, MonitoringSession.clipStartFor(c));
    }
}
