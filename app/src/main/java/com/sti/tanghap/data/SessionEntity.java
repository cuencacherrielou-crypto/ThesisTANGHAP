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

    public SessionEntity(@NonNull String id) {
        this.id = id;
    }
}
