package com.sti.tanghap.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Keeps coughs from people near the user out of the session.
 *
 * The device is held away from the face, so only the microphone hears coughs. The user is
 * the person closest to it, so their cough arrives loudest: sound drops about 6 dB each time
 * the distance doubles, so a cough from about three times farther away is about 10 dB quieter.
 * At the start of a session the user coughs a few times to calibrate their own level; later
 * coughs more than {@link #MARGIN_DB} quieter than that are treated as someone else's.
 * The cough TYPE is always decided by the AI model, never by this filter.
 */
public class NearbyCoughFilter {

    /** How much quieter than the user's calibrated cough a cough may be and still count. */
    public static final float MARGIN_DB = 10f;
    /** Calibration coughs asked for at the start of a session. */
    public static final int CALIBRATION_COUGHS = 3;

    /** The user's typical cough level in dBFS; null = no calibration, every cough counts. */
    private Float referenceDb;
    private boolean calibrating = false;
    private final List<Float> calibrationLevels = new ArrayList<>();

    /** @param savedReferenceDb the level from an earlier calibration, or null */
    public NearbyCoughFilter(Float savedReferenceDb) {
        this.referenceDb = savedReferenceDb;
    }

    public Float getReferenceDb() {
        return referenceDb;
    }

    /** Turns the filter off: every cough the model hears is counted. */
    public void disable() {
        calibrating = false;
        calibrationLevels.clear();
        referenceDb = null;
    }

    public void startCalibration() {
        calibrating = true;
        calibrationLevels.clear();
    }

    public boolean isCalibrating() {
        return calibrating;
    }

    public int calibrationCoughsHeard() {
        return calibrationLevels.size();
    }

    /** Records one calibration cough; returns true once enough have been heard. */
    public boolean addCalibrationCough(float levelDb) {
        if (!calibrating) return false;
        calibrationLevels.add(levelDb);
        return calibrationLevels.size() >= CALIBRATION_COUGHS;
    }

    /**
     * Ends calibration. The new level is the median of the calibration coughs, so one unusually
     * loud or soft cough does not decide it. With no calibration cough heard, the earlier level
     * (if any) is kept.
     *
     * @return true when a new level was set
     */
    public boolean finishCalibration() {
        calibrating = false;
        if (calibrationLevels.isEmpty()) return false;
        List<Float> sorted = new ArrayList<>(calibrationLevels);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        referenceDb = sorted.size() % 2 == 1
                ? sorted.get(mid)
                : (sorted.get(mid - 1) + sorted.get(mid)) / 2f;
        return true;
    }

    /** True when a cough this loud is likely the user's own (always true without calibration). */
    public boolean isFromUser(float levelDb) {
        return referenceDb == null || levelDb >= referenceDb - MARGIN_DB;
    }
}
