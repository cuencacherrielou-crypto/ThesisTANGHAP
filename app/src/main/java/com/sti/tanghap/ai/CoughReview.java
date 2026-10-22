package com.sti.tanghap.ai;

import com.sti.tanghap.data.SessionEntity;
import com.sti.tanghap.data.SessionEvent;
import java.util.List;

/**
 * Works out a session's cough count, cough finding and assessment from its event log.
 * Only coughs of type COUGH count: coughs filtered as "someone nearby" or removed by the
 * user are left out. Used when a session ends and again whenever the user removes or
 * restores a cough, so the saved result always matches the coughs that are counted.
 */
public final class CoughReview {

    public static final class Outcome {
        public final int coughCount;
        /** The latest counted cough or wheeze; null when there is none. */
        public final CoughClass finding;
        public final int findingConfidencePercent;
        public final AssessmentEngine.AssessmentResult assessment;

        Outcome(int coughCount, CoughClass finding, int findingConfidencePercent,
                AssessmentEngine.AssessmentResult assessment) {
            this.coughCount = coughCount;
            this.finding = finding;
            this.findingConfidencePercent = findingConfidencePercent;
            this.assessment = assessment;
        }

        /** Text saved as the session's cough finding, e.g. "Wet cough (87% confidence)". */
        public String findingText() {
            return finding != null ? finding.findingText(findingConfidencePercent) : CoughClass.NON_COUGH.label;
        }
    }

    private CoughReview() {}

    /** @param events the session's events in time order */
    public static Outcome evaluate(List<SessionEvent> events, int averageBpm, PatientProfile profile) {
        if (profile == null) profile = new PatientProfile();
        int count = 0;
        CoughClass finding = null;
        int confidence = 0;
        for (SessionEvent e : events) {
            if (e.type == SessionEvent.Type.COUGH) {
                count++;
            } else if (e.type != SessionEvent.Type.WHEEZE) {
                continue;
            }
            CoughClass c = e.type == SessionEvent.Type.WHEEZE ? CoughClass.WHEEZING : CoughClass.fromLabel(e.label);
            if (c != null) {
                finding = c;
                confidence = Math.round(e.value);
            }
        }
        BreathingAnalyzer.BreathingResult breathing = BreathingAnalyzer.classify(averageBpm, profile.ageYears);
        AssessmentEngine.AssessmentResult assessment =
                new AssessmentEngine().generatePreliminaryAssessment(breathing, finding, profile);
        return new Outcome(count, finding, confidence, assessment);
    }

    /** Re-evaluates a saved session after its event log changed, and marks it for re-upload. */
    public static void applyTo(SessionEntity s, List<SessionEvent> events, PatientProfile profile) {
        Outcome o = evaluate(events, s.averageBpm, profile);
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
