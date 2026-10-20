package com.sti.tanghap.domain;

import java.util.Locale;

/**
 * Chest movement (breathing depth) analysis derived from inhalation effort and duration.
 *
 * The device has no chest sensor, so depth is estimated from the pressure sensor:
 * a longer inhalation with stronger effort means the chest expanded more.
 * Thresholds are preliminary and should be confirmed by the evaluating physician.
 */
public class ChestMovementAnalyzer {

    public static final long SHALLOW_BELOW_MS = 1500;
    public static final long DEEP_FROM_MS = 3000;
    public static final float WEAK_EFFORT_BELOW_KPA = 2.0f;

    public enum Depth { NONE, SHALLOW, NORMAL, DEEP }

    public static class Result {
        public final Depth depth;
        public final boolean weakEffort;
        public final String label;
        public final String insight;

        Result(Depth depth, boolean weakEffort, String label, String insight) {
            this.depth = depth;
            this.weakEffort = weakEffort;
            this.label = label;
            this.insight = insight;
        }
    }

    public Result analyze(long avgInhaleMillis, float peakEffortKpa) {
        if (avgInhaleMillis <= 0) {
            return new Result(Depth.NONE, false, "No inhalation detected",
                    "Inhale through the mouthpiece to estimate chest movement.");
        }

        boolean weak = peakEffortKpa < WEAK_EFFORT_BELOW_KPA;
        String seconds = String.format(Locale.US, "%.1f s", avgInhaleMillis / 1000f);
        Depth depth;
        String label;
        String insight;

        if (avgInhaleMillis < SHALLOW_BELOW_MS) {
            depth = Depth.SHALLOW;
            label = "Shallow chest movement";
            insight = "Short inhalations (" + seconds + ") suggest limited chest expansion. "
                    + "A slow, deep breath helps the medicine reach the lungs.";
        } else if (avgInhaleMillis >= DEEP_FROM_MS) {
            depth = Depth.DEEP;
            label = "Deep chest movement";
            insight = "Long inhalations (" + seconds + ") suggest full chest expansion.";
        } else {
            depth = Depth.NORMAL;
            label = "Normal chest movement";
            insight = "Inhalation length (" + seconds + ") is within the expected range.";
        }

        if (weak) {
            label += " (weak effort)";
            insight += " Inhalation effort was weak, under "
                    + String.format(Locale.US, "%.1f", WEAK_EFFORT_BELOW_KPA) + " kPa.";
        }
        return new Result(depth, weak, label, insight);
    }
}
