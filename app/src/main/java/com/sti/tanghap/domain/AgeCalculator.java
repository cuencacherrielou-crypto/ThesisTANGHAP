package com.sti.tanghap.domain;

import java.util.Calendar;

/**
 * Works out the user's current age. The birthday saved at registration ("MM/dd/yyyy") is
 * preferred because the stored age number does not change as the user gets older.
 */
public final class AgeCalculator {

    /** Returned when neither the birthday nor the age field holds a usable value. */
    public static final int UNKNOWN = -1;

    private AgeCalculator() {}

    public static int resolve(Object birthday, Object ageField, Calendar today) {
        int fromBirthday = fromBirthday(birthday, today);
        if (fromBirthday != UNKNOWN) return fromBirthday;
        return fromAgeField(ageField);
    }

    /** Age in whole years from an "MM/dd/yyyy" birthday, or UNKNOWN. */
    public static int fromBirthday(Object birthday, Calendar today) {
        if (birthday == null) return UNKNOWN;
        String[] parts = String.valueOf(birthday).trim().split("/");
        if (parts.length != 3) return UNKNOWN;
        try {
            int month = Integer.parseInt(parts[0].trim());
            int day = Integer.parseInt(parts[1].trim());
            int year = Integer.parseInt(parts[2].trim());
            if (month < 1 || month > 12 || day < 1 || day > 31) return UNKNOWN;

            int age = today.get(Calendar.YEAR) - year;
            int todayMonth = today.get(Calendar.MONTH) + 1;
            int todayDay = today.get(Calendar.DAY_OF_MONTH);
            if (todayMonth < month || (todayMonth == month && todayDay < day)) age--;
            return age >= 0 && age <= 130 ? age : UNKNOWN;
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
    }

    private static int fromAgeField(Object ageField) {
        if (ageField instanceof Number) {
            int age = ((Number) ageField).intValue();
            return age >= 0 && age <= 130 ? age : UNKNOWN;
        }
        if (ageField == null) return UNKNOWN;
        try {
            int age = Integer.parseInt(String.valueOf(ageField).trim());
            return age >= 0 && age <= 130 ? age : UNKNOWN;
        } catch (NumberFormatException e) {
            return UNKNOWN; // e.g. "N/A" from Google sign-in
        }
    }
}
