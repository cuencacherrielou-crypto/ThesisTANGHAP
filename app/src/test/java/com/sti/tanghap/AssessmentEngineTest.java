package com.sti.tanghap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sti.tanghap.ai.AssessmentEngine;
import com.sti.tanghap.ai.AssessmentEngine.AssessmentResult;
import com.sti.tanghap.ai.BreathingAnalyzer;
import com.sti.tanghap.ai.CoughClass;
import com.sti.tanghap.ai.CoughEvidence;
import com.sti.tanghap.ai.OtcGuidance;
import com.sti.tanghap.ai.PatientProfile;
import com.sti.tanghap.data.SessionEvent;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Each possible condition needs all of its signs together; one sign alone (a wet cough, a
 * wheeze, a severe-sounding cough, fast breathing) never names a condition.
 */
public class AssessmentEngineTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    /** Session end: noon UTC (1_792_627_200_000 is a midnight UTC). */
    private static final long NOW = 1_792_627_200_000L + TimeUnit.HOURS.toMillis(12);

    private final AssessmentEngine engine = new AssessmentEngine();

    private static PatientProfile adult() {
        return new PatientProfile(30, false, false, false);
    }

    private static PatientProfile withAsthma() {
        return new PatientProfile(30, true, false, false);
    }

    private static PatientProfile withCopd() {
        return new PatientProfile(30, false, true, false);
    }

    /** This session's sounds, 10 s apart, ending at NOW. */
    private static List<SessionEvent> sounds(int dry, int wet, int severe, int wheezes) {
        List<SessionEvent> out = new ArrayList<>();
        long t = NOW - 10_000L * (dry + wet + severe + wheezes);
        for (int i = 0; i < dry; i++) out.add(new SessionEvent(t += 10_000, SessionEvent.Type.COUGH, 80, CoughClass.DRY_COUGH.label));
        for (int i = 0; i < wet; i++) out.add(new SessionEvent(t += 10_000, SessionEvent.Type.COUGH, 80, CoughClass.WET_COUGH.label));
        for (int i = 0; i < severe; i++) out.add(new SessionEvent(t += 10_000, SessionEvent.Type.COUGH, 80, CoughClass.SEVERE_COUGH.label));
        for (int i = 0; i < wheezes; i++) out.add(new SessionEvent(t += 10_000, SessionEvent.Type.WHEEZE, 80, CoughClass.WHEEZING.label));
        return out;
    }

    /** Two counted coughs on each of these earlier days (days before NOW). */
    private static List<Long> coughedDaysAgo(int... days) {
        List<Long> out = new ArrayList<>();
        for (int d : days) {
            out.add(NOW - d * DAY);
            out.add(NOW - d * DAY + 60_000);
        }
        return out;
    }

    private static List<Long> wheezedDaysAgo(int... days) {
        List<Long> out = new ArrayList<>();
        for (int d : days) out.add(NOW - d * DAY);
        return out;
    }

    private static CoughEvidence ev(int dry, int wet, int severe, int wheezes) {
        return ev(dry, wet, severe, wheezes, Collections.emptyList(), Collections.emptyList());
    }

    private static CoughEvidence ev(int dry, int wet, int severe, int wheezes, List<Long> priorCoughs, List<Long> priorWheezes) {
        return CoughEvidence.from(sounds(dry, wet, severe, wheezes), priorCoughs, priorWheezes, NOW, UTC);
    }

    /** A high-confidence breathing pattern at this rate (0 = not measured or not sure). */
    private AssessmentResult assess(int bpm, CoughEvidence ev, PatientProfile p) {
        return engine.assess(BreathingAnalyzer.classify(bpm, p.ageYears), ev, p);
    }

    // ---------- Bronchitis-like: productive cough AND lasting more than 5 days ----------

    @Test
    public void wetCoughsInOneSession_areNotBronchitis() {
        // 13:40 / 14:37 / 14:50 device sessions: wet coughs today only were called bronchitis
        AssessmentResult r = assess(16, ev(0, 5, 0, 0), adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
        assertEquals(OtcGuidance.WET_COUGH, r.medicineKey);   // the cough type still gets its OTC guidance
        assertTrue(r.recommendPharmacy);
        assertFalse(r.recommendClinic);
    }

    @Test
    public void singleWetCough_isNotBronchitis() {
        AssessmentResult r = engine.generatePreliminaryAssessment(BreathingAnalyzer.classify(16), CoughClass.WET_COUGH, adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
    }

    @Test
    public void wetCoughsLastingMoreThan5Days_areBronchitisLike() {
        AssessmentResult r = assess(16, ev(1, 4, 0, 0, coughedDaysAgo(6, 3), Collections.emptyList()), adult());
        assertEquals(AssessmentEngine.CATEGORY_BRONCHITIS, r.possibleCondition);
        assertEquals(OtcGuidance.WET_COUGH, r.medicineKey);
        assertTrue(r.recommendClinic);
        assertFalse(r.recommendHospital);
    }

    @Test
    public void coughingFor4Days_isNotYetBronchitis() {
        AssessmentResult r = assess(16, ev(0, 4, 0, 0, coughedDaysAgo(4, 2), Collections.emptyList()), adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
    }

    @Test
    public void mostlyDryCoughsLastingDays_areNotBronchitis() {
        AssessmentResult r = assess(16, ev(4, 3, 0, 0, coughedDaysAgo(8, 4), Collections.emptyList()), adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
        assertEquals(OtcGuidance.DRY_COUGH, r.medicineKey);
    }

    @Test
    public void twoWetCoughsLastingDays_areNotEnough() {
        AssessmentResult r = assess(16, ev(0, 2, 0, 0, coughedDaysAgo(8, 4), Collections.emptyList()), adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
    }

    @Test
    public void persistentWetCough_isBronchitisLike_seeADoctor() {
        AssessmentResult r = assess(16, ev(0, 3, 0, 0, coughedDaysAgo(24, 18, 12, 6), Collections.emptyList()), adult());
        assertEquals(AssessmentEngine.CATEGORY_BRONCHITIS, r.possibleCondition);
        assertEquals(OtcGuidance.PERSISTENT_COUGH, r.medicineKey);
        assertTrue(r.observations.contains(AssessmentEngine.PERSISTENT_OBSERVATION));
    }

    @Test
    public void persistentDryCough_isADoctorCheck_notBronchitis() {
        AssessmentResult r = assess(16, ev(3, 0, 0, 0, coughedDaysAgo(24, 18, 12, 6), Collections.emptyList()), adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
        assertTrue(r.recommendClinic);
        assertEquals(OtcGuidance.PERSISTENT_COUGH, r.medicineKey);
    }

    // ---------- Asthma-like: repeated wheeze, recurring on another day, with another symptom ----------

    @Test
    public void wheezingInOneSession_isNotAsthma() {
        // 13:40 / 14:38 device sessions: breathing sounds taken for wheezing
        AssessmentResult r = assess(16, ev(2, 1, 0, 4), adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
        assertTrue(r.wheezeDetected);
        assertTrue(String.join(" ", r.observations).contains("not enough to suggest asthma"));
        assertFalse(r.recommendClinic);
    }

    @Test
    public void recurringWheezeWithCoughing_isAsthmaLike() {
        AssessmentResult r = assess(16, ev(2, 0, 0, 2, Collections.emptyList(), wheezedDaysAgo(3)), adult());
        assertEquals(AssessmentEngine.CATEGORY_ASTHMA, r.possibleCondition);
        assertTrue(r.recommendClinic);
        assertFalse(r.recommendHospital);
        assertEquals(OtcGuidance.PRESCRIBED_RELIEVER, r.medicineKey);
    }

    @Test
    public void recurringWheezeWithoutAnotherSymptom_isNotAsthma() {
        AssessmentResult r = assess(16, ev(0, 0, 0, 3, Collections.emptyList(), wheezedDaysAgo(3)), adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
    }

    @Test
    public void wheezeEarlierTheSameDay_isNotRecurrent() {
        List<Long> earlierToday = Collections.singletonList(NOW - TimeUnit.HOURS.toMillis(2));
        AssessmentResult r = assess(16, ev(2, 0, 0, 2, Collections.emptyList(), earlierToday), adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
    }

    @Test
    public void wheezeMonthsAgo_isNotRecurrent() {
        AssessmentResult r = assess(16, ev(2, 0, 0, 2, Collections.emptyList(), wheezedDaysAgo(60)), adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
    }

    @Test
    public void wheezeHeardOnce_isNotAsthmaEvenWithAsthmaHistory() {
        AssessmentResult r = assess(16, ev(1, 0, 0, 1), withAsthma());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
    }

    @Test
    public void repeatedWheezeWithAsthmaHistory_isAsthmaLike() {
        AssessmentResult r = assess(16, ev(0, 0, 0, 2), withAsthma());
        assertEquals(AssessmentEngine.CATEGORY_ASTHMA, r.possibleCondition);
        assertTrue(r.recommendClinic);
    }

    @Test
    public void recurringWheezeWithRapidBreathing_recommendsHospital() {
        AssessmentResult r = assess(23, ev(0, 0, 0, 2, Collections.emptyList(), wheezedDaysAgo(2)), adult());
        assertEquals(AssessmentEngine.CATEGORY_ASTHMA, r.possibleCondition);
        assertTrue(r.recommendHospital);
    }

    @Test
    public void persistentCoughWithAsthmaHistory_isAsthmaLike() {
        AssessmentResult r = engine.generatePreliminaryAssessment(BreathingAnalyzer.classify(16), CoughClass.PERSISTENT_COUGH, withAsthma());
        assertEquals(AssessmentEngine.CATEGORY_ASTHMA, r.possibleCondition);
    }

    // ---------- Severe cough: coughing fits AND a cough lasting 2 weeks (CDC), like persistent ----------

    /** A coughing fit: {@code n} coughs of one type, 0.8 s apart, ending at NOW. */
    private static List<SessionEvent> fit(int n, CoughClass type) {
        List<SessionEvent> out = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) out.add(new SessionEvent(NOW - 800L * i, SessionEvent.Type.COUGH, 80, type.label));
        return out;
    }

    private static CoughEvidence fitEv(int n, CoughClass type, List<Long> priorCoughs) {
        return CoughEvidence.from(fit(n, type), priorCoughs, Collections.emptyList(), NOW, UTC);
    }

    @Test
    public void severeSoundingCoughs_areNotASevereCough() {
        // 14:40:05 / 14:50:45 device sessions: coughs the model called severe
        AssessmentResult r = assess(16, ev(0, 4, 3, 0), adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
        assertFalse(r.recommendClinic);
        assertEquals(OtcGuidance.WET_COUGH, r.medicineKey);
    }

    @Test
    public void coughingFitsDuringATwoWeekCough_areASevereCough_doctor_noMedicine() {
        AssessmentResult r = assess(16, fitEv(5, CoughClass.DRY_COUGH, coughedDaysAgo(14, 7)), adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
        assertTrue(r.observations.contains(AssessmentEngine.SEVERE_OBSERVATION));
        assertTrue(r.recommendClinic);
        assertFalse(r.recommendHospital);
        assertEquals(OtcGuidance.SEEK_CARE, r.medicineKey);
        assertFalse(r.recommendPharmacy);
    }

    @Test
    public void coughingFitsToday_areNotASevereCough() {
        AssessmentResult r = assess(16, fitEv(8, CoughClass.DRY_COUGH, Collections.emptyList()), adult());
        assertFalse(r.recommendClinic);
        assertEquals(OtcGuidance.DRY_COUGH, r.medicineKey);
    }

    @Test
    public void severeWetCough_isADoctorCheck_notBronchitis() {
        // A productive cough lasting days, but with coughing fits for 2 weeks: checked as severe
        AssessmentResult r = assess(16, fitEv(5, CoughClass.WET_COUGH, coughedDaysAgo(14, 7)), adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
        assertEquals(OtcGuidance.SEEK_CARE, r.medicineKey);
    }

    // ---------- Pneumonia-like: repeated coughing AND surely fast breathing (WHO IMCI) ----------

    @Test
    public void coughsWithFastBreathing_arePneumoniaLike_hospital() {
        AssessmentResult r = assess(26, ev(2, 0, 0, 0), adult());
        assertEquals(AssessmentEngine.CATEGORY_PNEUMONIA, r.possibleCondition);
        assertTrue(r.recommendHospital);
        assertEquals(OtcGuidance.SEEK_CARE, r.medicineKey);
    }

    @Test
    public void singleCoughWithFastBreathing_isClinicNotHospital() {
        AssessmentResult r = assess(26, ev(1, 0, 0, 0), adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
        assertTrue(r.recommendClinic);
        assertFalse(r.recommendHospital);
    }

    @Test
    public void coughsWithBreathingNotSurelyMeasured_areNotPneumonia() {
        // A medium or low confidence pattern arrives with bpm 0
        AssessmentResult r = assess(0, ev(4, 0, 0, 0), adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
        assertFalse(r.recommendHospital);
    }

    @Test
    public void fastBreathingAlone_isClinicNotHospital() {
        AssessmentResult r = assess(26, ev(0, 0, 0, 0), adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
        assertTrue(r.recommendClinic);
        assertFalse(r.recommendHospital);
        for (String o : r.observations) {
            assertFalse(o, o.toLowerCase().contains("cough"));   // only what was measured
        }
    }

    @Test
    public void wheezeIsNotACoughForThePneumoniaRule() {
        AssessmentResult r = assess(26, ev(0, 0, 0, 3), adult());
        assertFalse(AssessmentEngine.CATEGORY_PNEUMONIA.equals(r.possibleCondition));
        assertFalse(r.recommendHospital);
    }

    @Test
    public void pneumoniaComesFirstEvenWithCopd() {
        AssessmentResult r = assess(26, ev(0, 0, 3, 0), withCopd());
        assertEquals(AssessmentEngine.CATEGORY_PNEUMONIA, r.possibleCondition);
    }

    // ---------- COPD flare-up: COPD history AND two of more coughing / phlegm / breathlessness ----------

    @Test
    public void copdWithRapidBreathingOnly_isNotAFlare() {
        AssessmentResult r = assess(22, ev(0, 0, 0, 0), withCopd());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
        assertTrue(r.recommendClinic);   // rapid breathing is still checked
    }

    @Test
    public void copdWithWetCoughsOnly_isNotAFlare() {
        AssessmentResult r = assess(16, ev(0, 4, 0, 0), withCopd());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
    }

    @Test
    public void copdWithPhlegmAndRapidBreathing_isAFlare() {
        AssessmentResult r = assess(22, ev(0, 3, 0, 0), withCopd());
        assertEquals(AssessmentEngine.CATEGORY_COPD, r.possibleCondition);
        assertTrue(r.recommendClinic);
        assertFalse(r.recommendHospital);
    }

    @Test
    public void copdWithSevereCoughAndPhlegm_isAFlare() {
        AssessmentResult r = assess(16, fitEv(5, CoughClass.WET_COUGH, coughedDaysAgo(14, 7)), withCopd());
        assertEquals(AssessmentEngine.CATEGORY_COPD, r.possibleCondition);
    }

    @Test
    public void copdWithPersistentCoughAndRapidBreathing_isAFlare() {
        AssessmentResult r = assess(22, ev(3, 0, 0, 0, coughedDaysAgo(24, 18, 12, 6), Collections.emptyList()), withCopd());
        assertEquals(AssessmentEngine.CATEGORY_COPD, r.possibleCondition);
    }

    @Test
    public void copdUserWithStableReadings_staysBaseline() {
        AssessmentResult r = assess(16, ev(1, 0, 0, 0), withCopd());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
    }

    // ---------- Everything else ----------

    @Test
    public void dryCoughsGetCoughSuppressants() {
        AssessmentResult r = assess(16, ev(3, 1, 0, 0), adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
        assertEquals(OtcGuidance.DRY_COUGH, r.medicineKey);
        assertTrue(r.recommendPharmacy);
    }

    @Test
    public void nothingHeard_isStandard() {
        AssessmentResult r = assess(16, ev(0, 0, 0, 0), adult());
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
        assertEquals(OtcGuidance.NO_MEDICINE, r.medicineKey);
        assertFalse(r.recommendPharmacy);
        assertTrue(r.insight.contains(r.guidance));
    }

    @Test
    public void allergyHistory_doesNotRecommendMedicine() {
        AssessmentResult r = engine.generatePreliminaryAssessment(BreathingAnalyzer.classify(16), null, false, false, true);
        assertEquals(AssessmentEngine.CATEGORY_STANDARD, r.possibleCondition);
        assertFalse(r.recommendPharmacy);
    }

    @Test
    public void observationsCountTheCoughTypes() {
        AssessmentResult r = assess(16, ev(1, 4, 1, 0), adult());
        assertTrue(r.observations.contains("6 coughs detected (1 dry, 4 wet, 1 severe)."));
    }
}
