package com.sti.tanghap.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "sessions")
public class SessionEntity {
    @PrimaryKey @NonNull
    public String id;
    public String userId; // Firebase Auth UID of the session owner
    public long startTimeMillis;
    public long endTimeMillis;
    // Older sessions only (the air pressure sensor was removed); kept so saved sessions still load
    public float peakPressureKpa;
    public long inhaleDurationMillis;
    public int coughCount;
    public int actuationCount;
    public boolean synced;
    
    // Breathing Analysis
    public int averageBpm;
    public String detectedPattern;
    
    // Cough Analysis
    public String coughFinding;
    
    // AI-Assisted Assessment Fields
    public String aiPreliminaryInsight;
    public String aiObservations;
    public String otcRecommendation;
    public boolean riskLevelHigh;
    public String baselineComparison; // e.g., "10% higher than baseline"

    public String recommendation;

    // Older sessions only: down-sampled air pressure trace (comma-separated kPa values)
    public String pressureTrace;

    // ---- v4: time stamps and data quality ----
    // Older sessions only: milliseconds from startTimeMillis for each pressureTrace point
    public String pressureTraceTimes;
    // Time-stamped coughs, wheezes, inhaler presses, connection events, etc. (SessionEvent.encode)
    public String eventLog;
    // Bluetooth data gaps during the session: audio gaps (older sessions: lost air-pressure packets)
    public int droppedPackets;
    // Possible condition (AssessmentEngine.CATEGORY_*); never a diagnosis
    public String possibleCondition;
    // Medicine guidance shown for possibleCondition (otcRecommendation holds the same text)
    public String medicineKey;

    public SessionEntity(@NonNull String id) {
        this.id = id;
    }
}
