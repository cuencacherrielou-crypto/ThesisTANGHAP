package com.sti.tanghap.data;

import android.content.Context;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.sti.tanghap.data.AppDatabase;
import com.sti.tanghap.data.SessionDao;
import com.sti.tanghap.data.SessionEntity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SessionRepository {
    private final SessionDao dao;
    private final FirebaseFirestore fs = FirebaseFirestore.getInstance();
    private final FirebaseAuth auth = FirebaseAuth.getInstance();

    public SessionRepository(Context ctx) {
        dao = AppDatabase.getInstance(ctx).sessionDao();
    }

    public void saveLocal(SessionEntity s) {
        if (s.userId == null && auth.getCurrentUser() != null) {
            s.userId = auth.getCurrentUser().getUid();
        }
        dao.insert(s);
    }

    public List<SessionEntity> getAll() {
        if (auth.getCurrentUser() != null) {
            return dao.getAllForUser(auth.getCurrentUser().getUid());
        }
        return java.util.Collections.emptyList();
    }

    public List<SessionEntity> getUnsynced() {
        return dao.getUnsynced();
    }

    public void markSynced(String id) {
        dao.markSynced(id);
    }

    // Synchronous upload for WorkManager
    public void uploadSessionSync(SessionEntity s) throws Exception {
        if (auth.getCurrentUser() == null) return;
        String uid = auth.getCurrentUser().getUid();

        Map<String, Object> data = createSessionMap(s);

        // Wait for Firestore to complete before moving to the next one
        Tasks.await(fs.collection("users").document(uid)
                .collection("sessions").document(s.id)
                .set(data), 30, TimeUnit.SECONDS);
        
        markSynced(s.id);
    }

    // Async version for UI
    public void uploadSession(SessionEntity s) {
        if (auth.getCurrentUser() == null) return;
        String uid = auth.getCurrentUser().getUid();
        
        if (s.userId == null) {
            s.userId = uid;
        }

        Map<String, Object> data = createSessionMap(s);

        fs.collection("users").document(uid)
                .collection("sessions").document(s.id)
                .set(data)
                .addOnSuccessListener(v -> markSynced(s.id));
    }

    public void syncUserSessionsFromFirestore() {
        if (auth.getCurrentUser() == null) return;
        String uid = auth.getCurrentUser().getUid();

        fs.collection("users").document(uid)
                .collection("sessions")
                .get()
                .addOnSuccessListener(querySnapshots -> {
                    if (querySnapshots != null && !querySnapshots.isEmpty()) {
                        new Thread(() -> {
                            for (com.google.firebase.firestore.DocumentSnapshot doc : querySnapshots.getDocuments()) {
                                SessionEntity s = new SessionEntity(doc.getId());
                                s.userId = uid;
                                Long start = doc.getLong("startTimeMillis");
                                s.startTimeMillis = start != null ? start : System.currentTimeMillis();
                                Long end = doc.getLong("endTimeMillis");
                                s.endTimeMillis = end != null ? end : System.currentTimeMillis();
                                Double peak = doc.getDouble("peakPressureKpa");
                                s.peakPressureKpa = peak != null ? peak.floatValue() : 0.0f;
                                Long bpm = doc.getLong("averageBpm");
                                s.averageBpm = bpm != null ? bpm.intValue() : 0;
                                s.detectedPattern = doc.getString("detectedPattern");
                                s.aiPreliminaryInsight = doc.getString("aiPreliminaryInsight");
                                s.aiObservations = doc.getString("aiObservations");
                                Long cough = doc.getLong("coughCount");
                                s.coughCount = cough != null ? cough.intValue() : 0;
                                Long act = doc.getLong("actuationCount");
                                s.actuationCount = act != null ? act.intValue() : 0;
                                Long inhale = doc.getLong("inhaleDurationMillis");
                                s.inhaleDurationMillis = inhale != null ? inhale : 0;
                                s.otcRecommendation = doc.getString("otcRecommendation");
                                Boolean risk = doc.getBoolean("riskLevelHigh");
                                s.riskLevelHigh = risk != null && risk;
                                s.synced = true;

                                dao.insert(s);
                            }
                        }).start();
                    }
                });
    }

    private Map<String, Object> createSessionMap(SessionEntity s) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", s.id);
        data.put("startTimeMillis", s.startTimeMillis);
        data.put("endTimeMillis", s.endTimeMillis);
        data.put("peakPressureKpa", s.peakPressureKpa);
        data.put("inhaleDurationMillis", s.inhaleDurationMillis);
        data.put("coughCount", s.coughCount);
        data.put("actuationCount", s.actuationCount);
        
        // AI & Analysis fields
        data.put("averageBpm", s.averageBpm);
        data.put("detectedPattern", s.detectedPattern);
        data.put("coughFinding", s.coughFinding);
        data.put("aiPreliminaryInsight", s.aiPreliminaryInsight);
        data.put("aiObservations", s.aiObservations);
        data.put("otcRecommendation", s.otcRecommendation);
        data.put("riskLevelHigh", s.riskLevelHigh);
        data.put("baselineComparison", s.baselineComparison);
        data.put("recommendation", s.recommendation);
        
        return data;
    }
}
