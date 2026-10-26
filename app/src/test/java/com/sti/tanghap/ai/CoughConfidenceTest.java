package com.sti.tanghap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The 60% cough rule and the severe rule, with scores the model gave on TANGHAP device sounds. */
public class CoughConfidenceTest {

    /** Probabilities in CoughClass order: dry, non, persistent, severe, wet, wheezing. */
    private static AiCoughClassifier.Result result(float dry, float non, float pers, float sev, float wet, float whz) {
        float[] p = {dry, non, pers, sev, wet, whz};
        int best = AiCoughClassifier.best(p);
        return new AiCoughClassifier.Result(CoughClass.values()[best], p[best], p);
    }

    @Test
    public void coughSplitBetweenDryAndWetIsAConfidentCough() {
        // Real dry cough, 2026-10-26: dry 47%, wet 45%
        AiCoughClassifier.Result r = result(.47f, .03f, .01f, .01f, .45f, .04f);
        assertTrue(r.isConfidentCough());
        assertEquals(94, r.coughPercent());
        assertEquals(CoughClass.DRY_COUGH, r.coughType());
    }

    @Test
    public void weakSevereIsNotCalledSevere() {
        // Real cough wrongly called severe: severe 36%, dry 15%, wet 16%
        AiCoughClassifier.Result r = result(.15f, .27f, .01f, .36f, .16f, .05f);
        assertTrue(r.isConfidentCough());
        assertEquals(68, r.coughPercent());
        assertEquals(CoughClass.WET_COUGH, r.coughType());
    }

    @Test
    public void strongSevereIsSevere() {
        AiCoughClassifier.Result r = result(.05f, .09f, .01f, .76f, .07f, .02f);
        assertEquals(CoughClass.SEVERE_COUGH, r.coughType());
    }

    @Test
    public void coughBelowSixtyPercentIsNotRecorded() {
        // Top class dry, but only 55% cough in total
        AiCoughClassifier.Result r = result(.30f, .25f, .01f, .04f, .20f, .20f);
        assertEquals(CoughClass.DRY_COUGH, r.type);
        assertFalse(r.isConfidentCough());
    }

    @Test
    public void soundWhoseTopClassIsNotACoughIsNotRecorded() {
        // Non-cough sound on the no-cough device recording: non 35%, but 62% across cough types
        AiCoughClassifier.Result r = result(.28f, .35f, .01f, .03f, .31f, .03f);
        assertFalse(r.isConfidentCough());
    }
}
