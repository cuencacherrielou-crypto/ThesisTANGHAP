package com.sti.tanghap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.ai.CoughReview;
import com.sti.tanghap.ai.PatientProfile;
import com.sti.tanghap.data.SessionEvent;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The 17 TANGHAP device test sessions of 2026-10-26 (counted coughs and wheezes from their event
 * logs), assessed again in order with the earlier sessions as history, as the app does. The old
 * rules named bronchitis 4 times (wet coughs in one session) and asthma twice (wheezing in one
 * session, which the user confirmed was not wheezing). All of it happened on one day, with no
 * asthma or COPD history, so no session meets every sign of any condition.
 */
public class DeviceSessionReplayTest {

    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    private static final class Session {
        long start, end;
        int bpm;
        String oldCondition;
        final StringBuilder log = new StringBuilder();
    }

    private static List<Session> load() throws IOException {
        List<Session> out = new ArrayList<>();
        InputStream in = DeviceSessionReplayTest.class.getClassLoader().getResourceAsStream("device_sessions_2026-10-26.txt");
        assertNotNull(in);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f[0].equals("SESSION")) {
                    Session s = new Session();
                    s.start = Long.parseLong(f[1]);
                    s.end = Long.parseLong(f[2]);
                    s.bpm = Integer.parseInt(f[3]);
                    s.oldCondition = f[4];
                    out.add(s);
                } else if (!line.isEmpty()) {
                    out.get(out.size() - 1).log.append(line).append('\n');
                }
            }
        }
        return out;
    }

    @Test
    public void noConditionFromOneDayOfTesting() throws IOException {
        List<Session> sessions = load();
        assertEquals(17, sessions.size());
        PatientProfile adult = new PatientProfile(30, false, false, false);
        List<Long> priorCoughs = new ArrayList<>();
        List<Long> priorWheezes = new ArrayList<>();
        int oldNamed = 0;
        for (Session s : sessions) {
            List<SessionEvent> events = SessionEvent.decode(s.log.toString());
            CoughReview.Outcome o = CoughReview.evaluate(events, s.bpm, adult, priorCoughs, priorWheezes, s.end, MANILA);
            System.out.printf("%tT  coughs %2d  %-28s old: %-45s new: %s%s%n", s.start, o.coughCount, o.findingText(),
                    s.oldCondition, o.assessment.possibleCondition, o.assessment.recommendClinic ? " (clinic)" : "");
            if (!AssessmentEngine.CATEGORY_STANDARD.equals(s.oldCondition)) oldNamed++;
            assertEquals(AssessmentEngine.CATEGORY_STANDARD, o.assessment.possibleCondition);
            assertFalse(o.assessment.recommendHospital);
            for (SessionEvent e : events) {
                if (e.type == SessionEvent.Type.COUGH) priorCoughs.add(e.timeMillis);
                if (e.type == SessionEvent.Type.WHEEZE) priorWheezes.add(e.timeMillis);
            }
        }
        assertEquals(6, oldNamed);
    }
}
