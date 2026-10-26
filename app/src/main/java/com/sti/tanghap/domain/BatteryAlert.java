package com.sti.tanghap.domain;

/**
 * Decides when to tell the user to charge the TANGHAP device: once when the battery drops to
 * {@link #LOW_PERCENT}, once more at {@link #CRITICAL_PERCENT}. Nothing is repeated until the
 * device has been charged above {@link #RECHARGED_PERCENT}, so a reading that wavers around a
 * limit does not post the same notification again and again.
 */
public final class BatteryAlert {

    public static final int LOW_PERCENT = 20;
    public static final int CRITICAL_PERCENT = 10;
    public static final int RECHARGED_PERCENT = 30;

    public static final int LEVEL_NONE = 0;
    public static final int LEVEL_LOW = 1;
    public static final int LEVEL_CRITICAL = 2;

    private BatteryAlert() {}

    public static int levelFor(int percent) {
        if (percent <= CRITICAL_PERCENT) return LEVEL_CRITICAL;
        if (percent <= LOW_PERCENT) return LEVEL_LOW;
        return LEVEL_NONE;
    }

    /**
     * @param announced the level already notified since the last charge
     * @return the level to notify now, or {@link #LEVEL_NONE} when nothing new should be shown
     */
    public static int toAnnounce(int announced, int percent) {
        int level = levelFor(percent);
        return level > announced ? level : LEVEL_NONE;
    }

    /** The announced level to remember after this reading. */
    public static int remember(int announced, int percent) {
        if (percent > RECHARGED_PERCENT) return LEVEL_NONE;
        return Math.max(announced, levelFor(percent));
    }

    public static String title(int level) {
        return level == LEVEL_CRITICAL ? "TANGHAP battery very low" : "Charge your TANGHAP device";
    }

    public static String message(int level) {
        return level == LEVEL_CRITICAL
                ? "The battery is almost empty. Charge it now; the device may turn off during monitoring."
                : "The battery is low. Charge it soon so monitoring is not interrupted.";
    }
}
