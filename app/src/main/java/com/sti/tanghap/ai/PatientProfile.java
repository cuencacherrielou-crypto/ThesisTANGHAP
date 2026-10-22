package com.sti.tanghap.ai;

/**
 * The answers from registration, onboarding and Edit Profile that the assessment uses.
 * Built from the locally cached HealthPrefs so it also works offline.
 */
public class PatientProfile {
    /** Age in years; negative when unknown (adult breathing ranges are then used). */
    public int ageYears = -1;
    public boolean hasAsthma;
    public boolean hasCopd;
    /** Diagnosed allergies or dust/pollen triggers. Background only: never a reason for medicine. */
    public boolean hasCongestion;
    public boolean smokesOrVapes;
    public boolean previouslyHospitalized;

    public PatientProfile() {}

    public PatientProfile(int ageYears, boolean hasAsthma, boolean hasCopd, boolean hasCongestion) {
        this.ageYears = ageYears;
        this.hasAsthma = hasAsthma;
        this.hasCopd = hasCopd;
        this.hasCongestion = hasCongestion;
    }
}
