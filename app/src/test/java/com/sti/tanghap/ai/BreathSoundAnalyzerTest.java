package com.sti.tanghap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;

/** Breathing pattern from microphone audio: synthetic breathing, and parity with breath_detect.py. */
public class BreathSoundAnalyzerTest {

    private static final int SR = BreathSoundAnalyzer.SAMPLE_RATE;

    /**
     * Room noise with breaths: a loud phase (toward the mic) and a quiet phase per breath, as on
     * the device; when {@code voiced}, only a hummed tone in place of each breath.
     */
    private static BreathSoundAnalyzer breathing(double bpm, int seconds, boolean voiced) {
        Random rnd = new Random(7);
        double[] x = new double[seconds * SR];
        for (int i = 0; i < x.length; i++) x[i] = 0.0005 * rnd.nextGaussian();
        double period = 60.0 / bpm;
        for (double t = 0.5; t + period <= seconds; t += period) {
            int a = (int) (t * SR), loud = (int) (0.4 * period * SR), quiet = (int) (0.4 * period * SR);
            for (int i = 0; i < loud; i++) {
                double env = Math.sin(Math.PI * i / loud);
                double s = voiced ? Math.sin(2 * Math.PI * 180 * i / SR) + 0.5 * Math.sin(2 * Math.PI * 360 * i / SR)
                        : rnd.nextGaussian();
                x[a + i] += 0.05 * env * s;
            }
            int b = a + loud + (int) (0.05 * period * SR);
            for (int i = 0; !voiced && i < quiet && b + i < x.length; i++) {
                x[b + i] += 0.005 * Math.sin(Math.PI * i / quiet) * rnd.nextGaussian();
            }
        }
        BreathSoundAnalyzer an = new BreathSoundAnalyzer();
        for (double v : x) an.addSample(v);
        return an;
    }

    @Test
    public void normalBreathingIsMeasuredAsNormal() {
        BreathSoundAnalyzer.Result r = breathing(15, 60, false).analyzeAll();
        assertEquals(BreathSoundAnalyzer.State.MEASURED, r.state);
        assertEquals(15, r.rateBpm, 1.5);
        // loud phase toward the mic, quiet phase 20 dB lower: told apart surely
        assertEquals(BreathSoundAnalyzer.Confidence.HIGH, r.confidence());
    }

    @Test
    public void rapidAndSlowBreathingAreMeasured() {
        assertEquals(30, breathing(30, 60, false).analyzeAll().rateBpm, 3);
        assertEquals(8, breathing(8, 90, false).analyzeAll().rateBpm, 1);
    }

    @Test
    public void roomNoiseAloneGivesNoPattern() {
        BreathSoundAnalyzer an = new BreathSoundAnalyzer();
        Random rnd = new Random(1);
        for (int i = 0; i < 60 * SR; i++) an.addSample(0.0005 * rnd.nextGaussian());
        assertEquals(BreathSoundAnalyzer.State.NOT_CLEAR, an.analyzeAll().state);
    }

    @Test
    public void hummingIsNotBreathing() {
        assertEquals(BreathSoundAnalyzer.State.NOT_CLEAR, breathing(15, 60, true).analyzeAll().state);
    }

    @Test
    public void lessThanAWindowIsStillListening() {
        assertEquals(BreathSoundAnalyzer.State.LISTENING, breathing(15, 20, false).analyzeAll().state);
    }

    @Test
    public void liveReadingUsesOnlyRecentAudio() {
        BreathSoundAnalyzer an = breathing(15, 90, false);
        Random rnd = new Random(3);
        for (int i = 0; i < 60 * SR; i++) an.addSample(0.0005 * rnd.nextGaussian());   // then 60 s quiet
        assertEquals(BreathSoundAnalyzer.State.NOT_CLEAR, an.analyzeRecent(60).state);
        assertEquals(BreathSoundAnalyzer.State.MEASURED, an.analyzeAll().state);
    }

    @Test
    public void findPeaksMatchesScipy() {
        // Answers of scipy.signal.find_peaks on the same array (flat top -> its middle)
        double[] x = {0, 3, 1, 1, 4, 4, 4, 0, 2, 0};
        assertEquals(java.util.Arrays.asList(1, 5, 8), BreathSoundAnalyzer.findPeaks(x, 1, 3));
        assertEquals(java.util.Collections.singletonList(5), BreathSoundAnalyzer.findPeaks(x, 2.5, 1));
    }

    /**
     * TANGHAP device recordings (debug audio of sessions on 2026-10-26) give the same answer as the
     * Python reference (rate and rule), with the same cough times (tools/breath_java_ref.py). Runs only where the
     * recordings are (the TANGHAP ML workspace).
     */
    @Test
    public void deviceRecordingsMatchPythonReference() throws IOException {
        File root = new File(System.getProperty("user.home"), "tanghap_ml");
        assumeTrue(new File(root, "device_sessions").isDirectory());
        String[][] ref = {
                {"device_sessions/pull4/files/debug_audio/session_1792979983949.pcm", "4.8000 5.2500 5.9500 9.5500 10.3500 12.1500 27.1500 29.8000 32.9000", "none", "None"},
                {"device_sessions/pull4/files/debug_audio/session_1792980104017.pcm", "59.2500 59.8000 60.7500", "32.8767", "one phase"},
                {"device_sessions/pull4/files/debug_audio/session_1792980962121.pcm", "2.2000 3.1000 5.0000 5.4000 87.1500 101.0000 117.9500 118.4000 119.6500 121.4000", "none", "None"},
                {"device_sessions/pull4/files/debug_audio/session_1792983074084.pcm", "4.1000 5.8000 35.0000 35.4500 51.7500 65.4500 67.3000 78.0500 92.8000 94.1500 109.3500 109.8000 110.7500 130.2000 133.4500 138.9000", "11.4286", "two phases"},
                {"device_sessions/pull4/files/debug_audio/session_1792983274761.pcm", "", "none", "None"},
                {"device_sessions/pull4/files/debug_audio/session_1792983322328.pcm", "13.6000 15.5000 31.9500", "none", "None"},
                {"device_sessions/pull4/files/debug_audio/session_1792983398928.pcm", "143.6000", "30.0000", "one phase"},
                {"device_sessions/pull4/files/debug_audio/session_1792983869094.pcm", "0.4500 2.0000 9.2000 10.0500", "none", "None"},
                {"device_sessions/pull4/files/debug_audio/session_1792986431634.pcm", "", "none", "None"},
                {"device_sessions/pull4/files/debug_audio/session_1792991192097.pcm", "29.4500 31.0500 50.5000 51.7500", "17.9104", "loud phase"},
                {"device_sessions/pull5/files/debug_audio/session_1792992582975.pcm", "", "none", "None"},
                {"device_sessions/pull5/files/debug_audio/session_1792992650034.pcm", "40.7500 63.7500", "none", "None"},
                {"device_sessions/pull5/files/debug_audio/session_1792992727688.pcm", "9.6500 32.4000 39.9500", "40.0000", "one phase"},
                {"device_sessions/pull5/files/debug_audio/session_1792992779494.pcm", "46.1500", "none", "None"},
        };
        for (String[] row : ref) {
            File f = new File(root, row[0]);
            assumeTrue(f.isFile());
            BreathSoundAnalyzer an = new BreathSoundAnalyzer();
            an.addPcm(Files.readAllBytes(f.toPath()));
            for (String t : row[1].trim().isEmpty() ? new String[0] : row[1].split(" ")) {
                an.addCough(Math.round(Double.parseDouble(t) * SR));
            }
            BreathSoundAnalyzer.Result r = an.analyzeAll();
            if (row[2].equals("none")) {
                assertTrue(f.getName() + " gave " + r.rateBpm, r.state != BreathSoundAnalyzer.State.MEASURED);
            } else {
                assertEquals(f.getName(), BreathSoundAnalyzer.State.MEASURED, r.state);
                assertEquals(f.getName(), Double.parseDouble(row[2]), r.rateBpm, 0.01);
                assertEquals(f.getName(), row[3], r.rule.name().toLowerCase().replace('_', ' '));
            }
        }
    }
}
