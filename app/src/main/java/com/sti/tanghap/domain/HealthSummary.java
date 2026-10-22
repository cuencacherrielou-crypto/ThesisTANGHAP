package com.sti.tanghap.domain;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the Firestore user document into the read-only "Personal Health Information" summary.
 * Only answers the user actually gave are listed, under short labels instead of the
 * onboarding questions. Sections with no answers are left out.
 */
public final class HealthSummary {

    public static final class Item {
        public final String label;
        public final String value;

        Item(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    public static final class Section {
        public final String title;
        public final List<Item> items;

        Section(String title, List<Item> items) {
            this.title = title;
            this.items = Collections.unmodifiableList(items);
        }
    }

    private static final String OTHER_PREFIX = "Other:";

    private HealthSummary() {}

    public static List<Section> build(Map<String, Object> userDoc) {
        return build(userDoc, Calendar.getInstance());
    }

    /** @param today used to work out the current age from the birthday */
    @SuppressWarnings("unchecked")
    public static List<Section> build(Map<String, Object> userDoc, Calendar today) {
        List<Section> sections = new ArrayList<>();
        if (userDoc == null) return sections;

        Object healthObj = userDoc.get("healthProfile");
        Map<String, Object> h = healthObj instanceof Map ? (Map<String, Object>) healthObj : Collections.emptyMap();

        List<Item> personal = new ArrayList<>();
        addText(personal, "Full name", userDoc.get("fullName"));
        // Current age from the birthday, so it stays correct after each birthday
        int age = AgeCalculator.resolve(userDoc.get("birthday"), userDoc.get("age"), today);
        if (age != AgeCalculator.UNKNOWN) personal.add(new Item("Age", String.valueOf(age)));
        addText(personal, "Birthday", userDoc.get("birthday"));
        addText(personal, "Biological sex", h.get("biologicalSex"));
        Double height = number(h.get("heightCm"));
        Double weight = number(h.get("weightKg"));
        addMeasure(personal, "Height", h.get("heightCm"), height, "cm");
        addMeasure(personal, "Weight", h.get("weightKg"), weight, "kg");
        if (height != null && weight != null && height > 0 && weight > 0) {
            double bmi = bmi(height, weight);
            personal.add(new Item("BMI", String.format(Locale.US, "%.1f (%s)", bmi, bmiCategory(bmi))));
        }
        addSection(sections, "Personal Details", personal);

        List<Item> lifestyle = new ArrayList<>();
        addYesNo(lifestyle, "Smokes or vapes", h.get("isSmokerOrVaper"));
        addYesNo(lifestyle, "Frequent dust, smoke or pollution exposure", h.get("pollutionExposure"));
        addSection(sections, "Lifestyle & Exposure", lifestyle);

        List<Item> history = new ArrayList<>();
        addList(history, "Diagnosed conditions", conditions(h.get("diagnosedConditions")));
        addText(history, "Date diagnosed", h.get("diagnosisDate"));
        addYesNo(history, "Past hospitalization for breathing problems", h.get("previouslyHospitalized"));
        addYesNo(history, "Family history of respiratory conditions", h.get("familyRespiratoryHistory"));
        addSection(sections, "Respiratory History", history);

        List<Item> symptoms = new ArrayList<>();
        addText(symptoms, "Cough frequency", h.get("coughFrequency"));
        addList(symptoms, "Cough type", strings(h.get("coughTypes")));
        addYesNo(symptoms, "Shortness of breath", h.get("hasShortnessOfBreath"));
        addYesNo(symptoms, "Wheezing", h.get("hasWheezing"));
        addList(symptoms, "Symptom triggers", strings(h.get("respiratoryTriggers")));
        addSection(sections, "Symptoms", symptoms);

        List<Item> inhaler = new ArrayList<>();
        addText(inhaler, "Inhaler type", h.get("currentInhalerType"));
        addText(inhaler, "Inhaler use per day", h.get("inhalerUsageFreqPerDay"));
        Object doses = h.get("prescribedDosesPerDay");
        if (doses instanceof Number) {
            if (((Number) doses).intValue() > 0) {
                inhaler.add(new Item("Prescribed doses per day", String.valueOf(((Number) doses).intValue())));
            }
        } else {
            addText(inhaler, "Prescribed doses per day", doses);
        }
        addYesNo(inhaler, "Sometimes forgets to use inhaler", h.get("forgetsInhalerUsage"));
        addYesNo(inhaler, "Feels relief after using inhaler", h.get("feelsInhalerRelief"));
        addText(inhaler, "Other medications", h.get("medications"));
        addText(inhaler, "Allergy details", h.get("allergyDetails"));
        addSection(sections, "Inhaler & Medication", inhaler);

        Object contactObj = userDoc.get("emergencyContact");
        Map<String, Object> contact = contactObj instanceof Map ? (Map<String, Object>) contactObj : Collections.emptyMap();
        List<Item> emergency = new ArrayList<>();
        addText(emergency, "Name", contact.get("name"));
        addText(emergency, "Relationship", contact.get("relation"));
        addText(emergency, "Phone", contact.get("phone"));
        addSection(sections, "Emergency Contact", emergency);

        return sections;
    }

    public static double bmi(double heightCm, double weightKg) {
        double m = heightCm / 100.0;
        return weightKg / (m * m);
    }

    /** WHO adult BMI categories. */
    public static String bmiCategory(double bmi) {
        if (bmi < 18.5) return "Underweight";
        if (bmi < 25) return "Normal";
        if (bmi < 30) return "Overweight";
        return "Obese";
    }

    // --- helpers ---

    private static void addSection(List<Section> sections, String title, List<Item> items) {
        if (!items.isEmpty()) sections.add(new Section(title, items));
    }

    private static void addText(List<Item> items, String label, Object value) {
        String s = text(value);
        if (s != null) items.add(new Item(label, s));
    }

    private static void addYesNo(List<Item> items, String label, Object value) {
        if (value instanceof Boolean) items.add(new Item(label, (Boolean) value ? "Yes" : "No"));
    }

    private static void addMeasure(List<Item> items, String label, Object raw, Double parsed, String unit) {
        if (parsed != null) {
            if (parsed > 0) items.add(new Item(label, formatNumber(parsed) + " " + unit));
        } else {
            addText(items, label, raw);
        }
    }

    private static void addList(List<Item> items, String label, List<String> values) {
        if (!values.isEmpty()) items.add(new Item(label, String.join(", ", values)));
    }

    /** Trimmed text, or null for missing / placeholder values ("N/A" from social sign-in, "null"). */
    private static String text(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        if (s.isEmpty() || s.equalsIgnoreCase("null") || s.equalsIgnoreCase("N/A")) return null;
        return s;
    }

    private static Double number(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        String s = text(value);
        if (s == null) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatNumber(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.US, "%.1f", d);
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (!(value instanceof List)) return out;
        for (Object o : (List<?>) value) {
            String s = text(o);
            if (s != null && !out.contains(s)) out.add(s);
        }
        return out;
    }

    /** Shows "Other: X" as X and older "Mild Congestion" entries as Allergies. */
    private static List<String> conditions(Object value) {
        List<String> out = new ArrayList<>();
        for (String c : strings(value)) {
            String shown = c;
            if (c.startsWith(OTHER_PREFIX)) shown = c.substring(OTHER_PREFIX.length()).trim();
            else if (c.equals("Mild Congestion")) shown = "Allergies";
            if (!shown.isEmpty() && !out.contains(shown)) out.add(shown);
        }
        // "None" only makes sense on its own
        if (out.size() > 1) out.remove("None");
        return out;
    }
}
