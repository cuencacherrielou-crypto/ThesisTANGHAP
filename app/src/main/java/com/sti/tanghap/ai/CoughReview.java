package com.sti.tanghap.ai;

import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.data.SessionEvent;
import com.sti.tanghap.domain.CoughHistory;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

/**
 * Works out a session's cough count, cough finding and assessment from its event log.
 * Only coughs of type COUGH count: coughs filtered as "someone nearby" or removed by the
 * user are left out. Used when a session ends and again whenever the user removes or
 * restores a cough, so the saved result always matches the coughs that are counted.
 *
 * The cough type (dry / wet) comes from the sound model: the session's finding is its most
 * frequent cough type (or wheezing, when heard repeatedly), not just the last sound heard.
 * Whether the cough is severe or persistent comes from the cough history ({@link CoughHistory}):
 * coughing fits during a cough of 2 weeks or more make the finding a severe cough; coughing on
 * and off for 3 weeks or more makes it a persistent cough (wheezing is still reported as such). The assessment combines all the session's signs ({@link CoughEvidence}).
 */
public final class CoughReview {

    public static final String CONTINUOUS_EPISODES_OBSERVATION =
            "3 or more coughing episodes in the last 24 hours (continuous cough).";
    public static final String UNTYPED_COUGH_FINDING = "Cough (dry or wet not told apart)";
    public static final String CONTINUOUS_LONG_OBSERVATION =
            "Coughing for more than an hour in the last 24 hours (continuous cough).";

    public static final class Outcome {
        public final int coughCount;
        /** The main cough type or wheezing, or PERSISTENT_COUGH from the history; null when there is none. */
        public final CoughClass finding;
        public final int findingConfidencePercent;
        public final AssessmentEngine.AssessmentResult assessment;
        public final CoughHistory.Result history;
        public final CoughEvidence evidence;
        /** True when the finding is a persistent cough measured from the history. */
        public final boolean persistentFromHistory;
        /** True when the finding is a severe cough measured from the history. */
        public final boolean severeFromHistory;

        Outcome(int coughCount, CoughClass finding, int findingConfidencePercent,
                AssessmentEngine.AssessmentResult assessment, CoughHistory.Result history,
                CoughEvidence evidence, boolean persistentFromHistory, boolean severeFromHistory) {
            this.coughCount = coughCount;
            this.finding = finding;
            this.findingConfidencePercent = findingConfidencePercent;
            this.assessment = assessment;
            this.history = history;
            this.evidence = evidence;
            this.persistentFromHistory = persistentFromHistory;
            this.severeFromHistory = severeFromHistory;
        }

        /**
         * Text saved as the session's cough finding, e.g. "Wet cough (87% confidence)" or
         * "Persistent cough (coughing on and off for 24 days)".
         */
        public String findingText() {
            if (severeFromHistory) {
                return CoughClass.SEVERE_COUGH.label + " (coughing fits, coughing on and off for " + history.spellDays + " days)";
            }
            if (persistentFromHistory) {
                return CoughClass.PERSISTENT_COUGH.label + " (coughing on and off for " + history.spellDays + " days)";
            }
            if (finding != null) return finding.findingText(findingConfidencePercent);
            // only coughs an older build saved as "severe", whose dry / wet type was not kept
            return coughCount > 0 ? UNTYPED_COUGH_FINDING : CoughClass.NON_COUGH.label;
        }
    }

    private CoughReview() {}

    /** Without any earlier sessions (the history then holds only this session's coughs). */
    public static Outcome evaluate(List<SessionEvent> events, int averageBpm, PatientProfile profile) {
        long now = events.isEmpty() ? System.currentTimeMillis() : events.get(events.size() - 1).timeMillis;
        return evaluate(events, averageBpm, profile, Collections.emptyList(), Collections.emptyList(),
                now, ZoneId.systemDefault());
    }

    /**
     * @param events           the session's events in time order
     * @param priorCoughTimes  counted coughs of the user's earlier sessions (epoch ms)
     * @param priorWheezeTimes logged wheezes of the user's earlier sessions (epoch ms)
     * @param nowMillis        the moment assessed: the session's end
     */
    public static Outcome evaluate(List<SessionEvent> events, int averageBpm, PatientProfile profile,
                                   List<Long> priorCoughTimes, List<Long> priorWheezeTimes,
                                   long nowMillis, ZoneId zone) {
        if (profile == null) profile = new PatientProfile();
        CoughEvidence ev = CoughEvidence.from(events, priorCoughTimes, priorWheezeTimes, nowMillis, zone);
        CoughHistory.Result history = ev.history;

        CoughClass finding = finding(ev);
        int confidence = finding != null ? ev.meanPercent(finding) : 0;
        // Like a persistent cough, a severe cough is measured from the history; it needs care sooner
        boolean severeFromHistory = ev.severe && finding != CoughClass.WHEEZING;
        boolean persistentFromHistory = ev.persistent && finding != CoughClass.WHEEZING && !severeFromHistory;
        if (severeFromHistory) finding = CoughClass.SEVERE_COUGH;
        if (persistentFromHistory) finding = CoughClass.PERSISTENT_COUGH;

        BreathingAnalyzer.BreathingResult breathing = BreathingAnalyzer.classify(averageBpm, profile.ageYears);
        AssessmentEngine.AssessmentResult assessment = new AssessmentEngine().assess(breathing, ev, profile);
        if (ev.continuous) {
            assessment.observations.add(history.episodesLast24h >= CoughHistory.CONTINUOUS_EPISODES
                    ? CONTINUOUS_EPISODES_OBSERVATION : CONTINUOUS_LONG_OBSERVATION);
        }
        return new Outcome(ev.coughs, finding, confidence, assessment, history, ev, persistentFromHistory,
                severeFromHistory);
    }

    /**
     * Wheezing when heard repeatedly (or with no coughs), otherwise the most frequent cough type;
     * null when nothing was heard.
     */
    static CoughClass finding(CoughEvidence ev) {
        if (ev.repeatedWheeze() || (ev.coughs == 0 && ev.wheezes > 0)) return CoughClass.WHEEZING;
        return ev.mainCoughType();
    }

    /** Re-evaluates a saved session after its event log changed, and marks it for re-upload. */
    public static void applyTo(SessionEntity s, List<SessionEvent> events, PatientProfile profile,
                               List<Long> priorCoughTimes, List<Long> priorWheezeTimes) {
        Outcome o = evaluate(events, s.averageBpm, profile, priorCoughTimes, priorWheezeTimes,
                s.endTimeMillis, ZoneId.systemDefault());
        s.eventLog = SessionEvent.encode(events);
        s.coughCount = o.coughCount;
        s.coughFinding = o.findingText();
        s.aiPreliminaryInsight = o.assessment.insight;
        s.aiObservations = String.join(" • ", o.assessment.observations);
        s.possibleCondition = o.assessment.possibleCondition;
        s.medicineKey = o.assessment.medicineKey;
        s.otcRecommendation = o.assessment.medicationGuidance;
        s.riskLevelHigh = o.assessment.recommendHospital;
        s.recommendation = o.assessment.guidance;
        s.synced = false;
    }
}
