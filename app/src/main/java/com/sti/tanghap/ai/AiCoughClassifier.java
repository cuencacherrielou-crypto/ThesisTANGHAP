package com.sti.tanghap.ai;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class AiCoughClassifier {
    private static final String DATASET_PATH = "cough_reference_dataset.json";
    private final List<CoughProfile> referenceProfiles = new ArrayList<>();

    public AiCoughClassifier(Context context) throws IOException {
        loadDataset(context);
    }

    private void loadDataset(Context context) throws IOException {
        String json;
        try (InputStream is = context.getAssets().open(DATASET_PATH)) {
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            json = new String(buffer, StandardCharsets.UTF_8);
        }

        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                referenceProfiles.add(new CoughProfile(
                        obj.getString("type"),
                        obj.getString("label"),
                        obj.optInt("min_intensity", 0),
                        obj.optInt("max_intensity", Integer.MAX_VALUE),
                        obj.optInt("min_duration_ms", 0),
                        obj.optInt("max_duration_ms", Integer.MAX_VALUE),
                        obj.optInt("min_count", 0)
                ));
            }
        } catch (JSONException e) {
            throw new IOException("Failed to parse dataset JSON", e);
        }
    }

    public ClassificationResult classify(int intensity, long durationMs, int recentCount) {
        CoughProfile bestMatch = null;
        float highestScore = -1;

        for (CoughProfile profile : referenceProfiles) {
            float score = calculateMatchScore(profile, intensity, durationMs, recentCount);
            if (score > highestScore) {
                highestScore = score;
                bestMatch = profile;
            }
        }

        if (bestMatch != null && highestScore > 0.5) {
            return new ClassificationResult(bestMatch.label, highestScore);
        } else {
            return new ClassificationResult("Unknown Cough Type", 0.0f);
        }
    }

    private float calculateMatchScore(CoughProfile profile, int intensity, long duration, int count) {
        float score = 0;
        int criteriaCount = 0;

        // Intensity check
        if (intensity >= profile.minIntensity && intensity <= profile.maxIntensity) {
            score += 1.0;
        } else if (intensity > profile.minIntensity - 20 && intensity < profile.maxIntensity + 20) {
            score += 0.5; // Near match
        }
        criteriaCount++;

        // Duration check
        if (duration >= profile.minDuration && duration <= profile.maxDuration) {
            score += 1.0;
        } else if (duration > profile.minDuration - 100 && duration < profile.maxDuration + 100) {
            score += 0.5;
        }
        criteriaCount++;

        // Count check (for persistent)
        if (profile.minCount > 0) {
            if (count >= profile.minCount) score += 1.0;
            criteriaCount++;
        }

        return score / criteriaCount;
    }

    private static class CoughProfile {
        final String type, label;
        final int minIntensity, maxIntensity;
        final int minDuration, maxDuration;
        final int minCount;

        CoughProfile(String type, String label, int minInt, int maxInt, int minDur, int maxDur, int minCount) {
            this.type = type;
            this.label = label;
            this.minIntensity = minInt;
            this.maxIntensity = maxInt;
            this.minDuration = minDur;
            this.maxDuration = maxDur;
            this.minCount = minCount;
        }
    }

    public static class ClassificationResult {
        public final String label;
        public final float confidence;

        public ClassificationResult(String label, float confidence) {
            this.label = label;
            this.confidence = confidence;
        }
    }
}
