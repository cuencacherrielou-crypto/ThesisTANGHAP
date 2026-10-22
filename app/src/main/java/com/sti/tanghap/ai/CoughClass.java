package com.sti.tanghap.ai;

/**
 * Sound classes of the on-device cough model (tanghap_model.tflite).
 * The order MUST match the model's output index order: the training labels were
 * encoded alphabetically (sklearn LabelEncoder), so index 0 is dry_cough.
 */
public enum CoughClass {
    DRY_COUGH("Dry cough", true),
    NON_COUGH("No significant cough", false),
    PERSISTENT_COUGH("Persistent cough", true),
    SEVERE_COUGH("Severe cough", true),
    WET_COUGH("Wet cough", true),
    WHEEZING("Wheezing", false);

    public final String label;
    /** True for the four cough classes; wheezing and non-cough sounds are not coughs. */
    public final boolean isCough;

    CoughClass(String label, boolean isCough) {
        this.label = label;
        this.isCough = isCough;
    }

    /** The class with this display label (as saved in the event log), or null. */
    public static CoughClass fromLabel(String label) {
        for (CoughClass c : values()) {
            if (c.label.equals(label)) return c;
        }
        return null;
    }

    /** Text for the live AI findings card, e.g. "Cough Detected: Wet cough (87%)". */
    public String displayText(int confidencePercent) {
        String suffix = " (" + confidencePercent + "%)";
        if (this == WHEEZING) return "Wheezing-like Sound Detected" + suffix;
        if (isCough) return "Cough Detected: " + label + suffix;
        return label + suffix;
    }

    /** Text saved with the session, e.g. "Wet cough (87% confidence)". */
    public String findingText(int confidencePercent) {
        return label + " (" + confidencePercent + "% confidence)";
    }
}
