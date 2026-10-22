package com.sti.tanghap.ai;

/**
 * Medicine guidance shown under each assessment result.
 *
 * Medicines are named by generic name only (Generics Act of 1988, RA 6675) and limited to
 * single-ingredient products sold over the counter in the Philippines and covered by the
 * US FDA OTC cough/cold monograph or NICE NG120 (acute cough). Age limits follow the UK MHRA
 * (2009) and EMA (2010) restrictions on cough medicines for children. Doses are deliberately
 * not given: the patient follows the package label, which depends on the product's strength.
 * Findings that need a doctor (pneumonia-like, asthma-like, COPD, persistent cough) get no
 * OTC medicine at all, and allergies are background information only.
 */
public final class OtcGuidance {

    /** Medicine keys, chosen by {@link AssessmentEngine}. */
    public static final String SEEK_CARE = "seek_care";
    public static final String PRESCRIBED_RELIEVER = "prescribed_reliever";
    public static final String COPD_ACTION_PLAN = "copd_action_plan";
    public static final String WET_COUGH = "wet_cough";
    public static final String PERSISTENT_COUGH = "persistent_cough";
    public static final String DRY_COUGH = "dry_cough";
    public static final String CHILD_UNDER_6 = "child_under_6";
    public static final String NO_MEDICINE = "no_medicine";

    public static final String RECOMMENDATION_ONLY =
            "This is only a recommendation, not a prescription. It is still best to consult "
                    + "a doctor or pharmacist before taking any medicine.";

    static final String AGE_12_PLUS =
            "For adults and children 12 and over. Ages 6 to 11: only if a doctor or pharmacist advises.";

    private static final String TEXT_DRY = "Suggested for a dry cough (no phlegm):\n"
            + "• Dextromethorphan (cough suppressant): reduces the urge to cough. " + AGE_12_PLUS
            + " Do not take it if you use an MAOI antidepressant or stopped one in the last 14 days. "
            + "Ask a pharmacist first if you take any other antidepressant. May cause drowsiness or dizziness.\n"
            + "• Honey in warm water or tea: soothes the throat. Never give honey to babies under 1 year.\n"
            + "• Throat lozenges and warm fluids: ease throat irritation. Lozenges are not for young "
            + "children, who can choke on them.\n"
            + "Do not use expectorants or mucolytics (guaifenesin, carbocisteine, ambroxol) for a dry cough.";

    private static final String TEXT_WET = "Suggested for a wet cough (with phlegm):\n"
            + "• Guaifenesin (expectorant): loosens phlegm so it is easier to cough out. " + AGE_12_PLUS
            + " Drink plenty of water while taking it.\n"
            + "• Carbocisteine or Ambroxol (mucolytic): thins thick phlegm. Take only one of them. "
            + "Not for children under 2. Ask a doctor first if you have or had a stomach ulcer. "
            + "Stop and see a doctor if a skin rash appears.\n"
            + "• Drink plenty of fluids; this also helps loosen phlegm.\n"
            + "Do not use a cough suppressant (dextromethorphan) for a wet cough. Coughing helps clear the phlegm.\n"
            + "Antibiotics are not needed for most coughs and need a doctor's prescription.\n"
            + "See a doctor if the phlegm has blood in it, or the cough comes with a fever or breathlessness.";

    private static final String TEXT_PERSISTENT = "Persistent cough: please see a doctor.\n"
            + "• No OTC medicine is suggested. Cough medicines are not meant for long-term use and can "
            + "hide the cause of the cough.\n"
            + "• A cough lasting 2 weeks or more should be checked by a doctor. The DOH advises TB "
            + "testing for any cough of 2 weeks or more; TB testing and treatment are free at "
            + "government health centers.\n"
            + "• For comfort while waiting for a check-up: honey (not for babies under 1 year), "
            + "warm fluids, and avoiding smoke.";

    private static final String TEXT_SEEK_CARE = "No OTC medicine is suggested. Seek medical care now.\n"
            + "• OTC cough medicines can hide warning signs and are not advised here.\n"
            + "• Do not take antibiotics unless a doctor prescribes them after checking you.\n"
            + "• Go to the nearest emergency room if you have difficulty breathing, chest pain, "
            + "coughing up blood, bluish lips or face, a high fever, or confusion.";

    private static final String TEXT_RELIEVER = "No OTC medicine is suggested.\n"
            + "• If a doctor prescribed you a reliever inhaler (for example salbutamol), use it exactly "
            + "as instructed. Never use another person's inhaler.\n"
            + "• OTC cough medicines do not open the airways and are not advised.\n"
            + "• Seek emergency care if breathing does not improve after using your reliever, "
            + "or if it becomes hard to speak or walk.";

    private static final String TEXT_COPD = "No OTC medicine is suggested.\n"
            + "• Use your prescribed inhalers and follow the COPD action plan from your doctor.\n"
            + "• OTC cough suppressants are not advised; they can make it harder to clear phlegm.\n"
            + "• Contact your doctor within 24 hours if you are more breathless than usual, or your "
            + "phlegm increases or changes color.\n"
            + "• Go to the nearest emergency room if you have severe breathlessness, chest pain, "
            + "confusion, or bluish lips.";

    private static final String TEXT_CHILD = "Child under 6 years old: no OTC cough medicine.\n"
            + "• OTC cough and cold medicines are not advised at this age. Please see a doctor or pediatrician.\n"
            + "• Comfort care: plenty of fluids and rest. For children 1 year and older, a little honey "
            + "can soothe the throat. Never give honey to babies under 1 year.\n"
            + "• Seek care right away if the child breathes fast, the chest pulls in with each breath, "
            + "or the child cannot drink or feed.";

    private static final String TEXT_NONE = "No medicine is needed right now:\n"
            + "• Rest, drink plenty of fluids, and avoid smoke and dust.\n"
            + "• Keep taking any medicine your doctor prescribed.";

    private static final String GENERAL_SAFETY = "Safety reminders:\n"
            + "• Choose single-ingredient products and follow the dose on the package label.\n"
            + "• Never take two products that contain the same medicine.\n"
            + "• Children under 6: do not give OTC cough or cold medicines unless a doctor advises.\n"
            + "• Pregnant or breastfeeding: ask a doctor before taking any medicine.\n"
            + "• Stop and see a doctor if the cough lasts more than 7 days, comes back, or comes with "
            + "a fever, rash, or persistent headache.";

    private static final String CHRONIC_LUNG_WARNING =
            "• Your profile lists asthma or COPD: ask your doctor before taking any cough medicine.";

    private OtcGuidance() {}

    /**
     * Picks the medicine key for a finding. Children under 6 never get OTC cough medicine.
     *
     * @param ageYears the user's age, or a negative value when unknown
     */
    public static String select(String medicineKey, int ageYears) {
        boolean childUnder6 = ageYears >= 0 && ageYears < 6;
        if (childUnder6 && (DRY_COUGH.equals(medicineKey) || WET_COUGH.equals(medicineKey)
                || PERSISTENT_COUGH.equals(medicineKey))) {
            return CHILD_UNDER_6;
        }
        return medicineKey;
    }

    /** True when the guidance names an OTC medicine the user may buy. */
    public static boolean suggestsMedicine(String medicineKey) {
        return DRY_COUGH.equals(medicineKey) || WET_COUGH.equals(medicineKey);
    }

    /**
     * @param medicineKey     one of the keys above (after {@link #select})
     * @param hasAsthmaOrCopd from the user's health profile
     */
    public static String describe(String medicineKey, boolean hasAsthmaOrCopd) {
        String body;
        if (DRY_COUGH.equals(medicineKey)) body = TEXT_DRY;
        else if (WET_COUGH.equals(medicineKey)) body = TEXT_WET;
        else if (PERSISTENT_COUGH.equals(medicineKey)) body = TEXT_PERSISTENT;
        else if (PRESCRIBED_RELIEVER.equals(medicineKey)) body = TEXT_RELIEVER;
        else if (COPD_ACTION_PLAN.equals(medicineKey)) body = TEXT_COPD;
        else if (CHILD_UNDER_6.equals(medicineKey)) body = TEXT_CHILD;
        else if (NO_MEDICINE.equals(medicineKey)) body = TEXT_NONE;
        else body = TEXT_SEEK_CARE; // unknown keys fall back to the safest advice

        StringBuilder sb = new StringBuilder(body);
        if (suggestsMedicine(medicineKey)) {
            sb.append("\n\n").append(GENERAL_SAFETY);
            if (hasAsthmaOrCopd) sb.append("\n").append(CHRONIC_LUNG_WARNING);
        }
        sb.append("\n\n").append(RECOMMENDATION_ONLY);
        return sb.toString();
    }
}
