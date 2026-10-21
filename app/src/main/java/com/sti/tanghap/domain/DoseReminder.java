package com.sti.tanghap.domain;

/**
 * Compares inhaler actuations recorded today with the prescribed doses per day.
 *
 * Doses are assumed to be spread evenly between 8:00 AM and 8:00 PM. Before the
 * window closes, being behind the expected count is a late dose; after it closes,
 * any shortfall is a missed dose.
 */
public class DoseReminder {

    public static final int WINDOW_START_MIN = 8 * 60;   // 8:00 AM
    public static final int WINDOW_END_MIN = 20 * 60;    // 8:00 PM

    public enum Status { NONE, LATE, MISSED }

    public static class Result {
        public final Status status;
        public final int expectedSoFar;
        public final int taken;
        public final int prescribed;
        public final String title;
        public final String message;

        Result(Status status, int expectedSoFar, int taken, int prescribed, String title, String message) {
            this.status = status;
            this.expectedSoFar = expectedSoFar;
            this.taken = taken;
            this.prescribed = prescribed;
            this.title = title;
            this.message = message;
        }
    }

    /** Number of doses that should have been taken by this minute of the day. */
    public static int expectedSoFar(int prescribedPerDay, int minuteOfDay) {
        if (prescribedPerDay <= 0 || minuteOfDay < WINDOW_START_MIN) return 0;
        if (minuteOfDay >= WINDOW_END_MIN) return prescribedPerDay;
        float fraction = (minuteOfDay - WINDOW_START_MIN) / (float) (WINDOW_END_MIN - WINDOW_START_MIN);
        // A dose is due at the start of each equal slot: 2/day -> 8:00 AM and 2:00 PM.
        int due = (int) Math.floor(fraction * prescribedPerDay) + 1;
        return Math.min(due, prescribedPerDay);
    }

    /** Minutes after a dose is due before it counts as late. */
    public static final int GRACE_MIN = 60;

    public Result evaluate(int prescribedPerDay, int takenToday, int minuteOfDay) {
        int checkMinute = minuteOfDay >= WINDOW_END_MIN ? minuteOfDay : minuteOfDay - GRACE_MIN;
        int expected = expectedSoFar(prescribedPerDay, checkMinute);
        if (prescribedPerDay <= 0 || takenToday >= expected) {
            return new Result(Status.NONE, expected, takenToday, prescribedPerDay, null, null);
        }
        int behind = expected - takenToday;
        if (minuteOfDay >= WINDOW_END_MIN) {
            return new Result(Status.MISSED, expected, takenToday, prescribedPerDay,
                    "Missed inhaler dose",
                    "You recorded " + takenToday + " of " + prescribedPerDay
                            + " prescribed doses today. If you missed a dose, follow your doctor's instructions.");
        }
        return new Result(Status.LATE, expected, takenToday, prescribedPerDay,
                "Inhaler dose due",
                behind + (behind == 1 ? " dose is" : " doses are") + " due based on your schedule ("
                        + takenToday + " of " + prescribedPerDay + " taken today).");
    }
}
