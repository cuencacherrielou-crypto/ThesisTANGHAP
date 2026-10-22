package com.sti.tanghap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sti.tanghap.domain.HealthSummary;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HealthSummaryTest {

    private static Map<String, Object> userDoc(Map<String, Object> health) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("fullName", "Juan Dela Cruz");
        doc.put("age", "21");
        doc.put("birthday", "01/15/2005");
        doc.put("healthProfile", health);
        return doc;
    }

    private static HealthSummary.Section section(List<HealthSummary.Section> sections, String title) {
        for (HealthSummary.Section s : sections) if (s.title.equals(title)) return s;
        return null;
    }

    private static String value(List<HealthSummary.Section> sections, String label) {
        for (HealthSummary.Section s : sections) {
            for (HealthSummary.Item i : s.items) if (i.label.equals(label)) return i.value;
        }
        return null;
    }

    @Test
    public void fullProfile_listsEveryAnswerWithoutQuestions() {
        Map<String, Object> h = new HashMap<>();
        h.put("biologicalSex", "Male");
        h.put("heightCm", "170");
        h.put("weightKg", "65.5");
        h.put("isSmokerOrVaper", false);
        h.put("pollutionExposure", true);
        h.put("diagnosedConditions", Arrays.asList("Asthma", "Other: Sinusitis"));
        h.put("diagnosisDate", "03/02/2015");
        h.put("previouslyHospitalized", true);
        h.put("familyRespiratoryHistory", false);
        h.put("coughFrequency", "Daily");
        h.put("coughTypes", Arrays.asList("Dry", "Persistent"));
        h.put("hasShortnessOfBreath", true);
        h.put("hasWheezing", true);
        h.put("respiratoryTriggers", Arrays.asList("Dust", "Cold weather"));
        h.put("currentInhalerType", "Salbutamol");
        h.put("inhalerUsageFreqPerDay", "2 times/day");
        h.put("prescribedDosesPerDay", 2L);
        h.put("forgetsInhalerUsage", true);
        h.put("feelsInhalerRelief", true);
        h.put("medications", "Montelukast 10mg daily");
        h.put("allergyDetails", "Penicillin");
        Map<String, Object> doc = userDoc(h);
        Map<String, Object> contact = new HashMap<>();
        contact.put("name", "Maria Dela Cruz");
        contact.put("relation", "Mother");
        contact.put("phone", "09171234567");
        doc.put("emergencyContact", contact);

        List<HealthSummary.Section> s = HealthSummary.build(doc);

        assertEquals(6, s.size());
        assertEquals("Juan Dela Cruz", value(s, "Full name"));
        assertEquals("21", value(s, "Age"));
        assertEquals("Male", value(s, "Biological sex"));
        assertEquals("170 cm", value(s, "Height"));
        assertEquals("65.5 kg", value(s, "Weight"));
        assertEquals("22.7 (Normal)", value(s, "BMI"));
        assertEquals("No", value(s, "Smokes or vapes"));
        assertEquals("Yes", value(s, "Frequent dust, smoke or pollution exposure"));
        assertEquals("Asthma, Sinusitis", value(s, "Diagnosed conditions"));
        assertEquals("03/02/2015", value(s, "Date diagnosed"));
        assertEquals("Yes", value(s, "Past hospitalization for breathing problems"));
        assertEquals("No", value(s, "Family history of respiratory conditions"));
        assertEquals("Daily", value(s, "Cough frequency"));
        assertEquals("Dry, Persistent", value(s, "Cough type"));
        assertEquals("Yes", value(s, "Shortness of breath"));
        assertEquals("Yes", value(s, "Wheezing"));
        assertEquals("Dust, Cold weather", value(s, "Symptom triggers"));
        assertEquals("Salbutamol", value(s, "Inhaler type"));
        assertEquals("2 times/day", value(s, "Inhaler use per day"));
        assertEquals("2", value(s, "Prescribed doses per day"));
        assertEquals("Yes", value(s, "Sometimes forgets to use inhaler"));
        assertEquals("Yes", value(s, "Feels relief after using inhaler"));
        assertEquals("Montelukast 10mg daily", value(s, "Other medications"));
        assertEquals("Penicillin", value(s, "Allergy details"));
        assertEquals("Maria Dela Cruz", value(s, "Name"));
        assertEquals("Mother", value(s, "Relationship"));
        assertEquals("09171234567", value(s, "Phone"));

        for (HealthSummary.Section sec : s) {
            for (HealthSummary.Item i : sec.items) {
                assertTrue("label reads like a question: " + i.label, !i.label.endsWith("?"));
            }
        }
    }

    @Test
    public void unansweredFields_areLeftOut() {
        Map<String, Object> h = new HashMap<>();
        h.put("biologicalSex", "Female");
        h.put("heightCm", "");
        h.put("weightKg", "");
        h.put("diagnosedConditions", new ArrayList<String>());
        h.put("diagnosisDate", "");
        h.put("coughTypes", new ArrayList<String>());
        h.put("respiratoryTriggers", new ArrayList<String>());
        h.put("currentInhalerType", "");
        h.put("prescribedDosesPerDay", 0);
        h.put("medications", "");
        Map<String, Object> doc = userDoc(h);
        doc.put("age", "N/A");
        doc.put("birthday", "null");

        List<HealthSummary.Section> s = HealthSummary.build(doc);

        assertEquals(1, s.size());
        assertEquals("Personal Details", s.get(0).title);
        assertEquals(2, s.get(0).items.size()); // full name and sex
        assertNull(value(s, "Age"));
        assertNull(value(s, "Birthday"));
        assertNull(value(s, "BMI"));
        assertNull(value(s, "Prescribed doses per day"));
    }

    @Test
    public void noDocument_givesNoSections() {
        assertTrue(HealthSummary.build(null).isEmpty());
        assertTrue(HealthSummary.build(new HashMap<>()).isEmpty());
    }

    @Test
    public void legacyAndDuplicateConditions_areCleanedUp() {
        Map<String, Object> h = new HashMap<>();
        h.put("diagnosedConditions", Arrays.asList("Mild Congestion", "Allergies", "None", "Other:  "));
        List<HealthSummary.Section> s = HealthSummary.build(userDoc(h));
        assertEquals("Allergies", value(s, "Diagnosed conditions"));
    }

    @Test
    public void onlyNone_isShown() {
        Map<String, Object> h = new HashMap<>();
        h.put("diagnosedConditions", Arrays.asList("None"));
        assertEquals("None", value(HealthSummary.build(userDoc(h)), "Diagnosed conditions"));
    }

    @Test
    public void nonNumericMeasurements_areShownAsTyped_withoutBmi() {
        Map<String, Object> h = new HashMap<>();
        h.put("heightCm", "5'7");
        h.put("weightKg", "60");
        List<HealthSummary.Section> s = HealthSummary.build(userDoc(h));
        assertEquals("5'7", value(s, "Height"));
        assertEquals("60 kg", value(s, "Weight"));
        assertNull(value(s, "BMI"));
    }

    @Test
    public void bmiCategories_followWhoCutoffs() {
        assertEquals("Underweight", HealthSummary.bmiCategory(18.4));
        assertEquals("Normal", HealthSummary.bmiCategory(18.5));
        assertEquals("Normal", HealthSummary.bmiCategory(24.9));
        assertEquals("Overweight", HealthSummary.bmiCategory(25));
        assertEquals("Obese", HealthSummary.bmiCategory(30));
        assertEquals(22.49, HealthSummary.bmi(170, 65), 0.01);
    }

    @Test
    public void emptySectionsAreSkipped_andLifestyleShowsWhenAnswered() {
        Map<String, Object> h = new HashMap<>();
        h.put("isSmokerOrVaper", true);
        List<HealthSummary.Section> s = HealthSummary.build(userDoc(h));
        assertEquals("Yes", value(s, "Smokes or vapes"));
        assertNull(section(s, "Symptoms"));
        assertNull(section(s, "Emergency Contact"));
    }
}
