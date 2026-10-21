package com.sti.tanghap.sync;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.sti.tanghap.data.SessionRepository;
import com.sti.tanghap.data.SessionEntity;
import java.util.List;

public class SyncWorker extends Worker {
    private static final String TAG = "SyncWorker";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Starting sync process...");
        SessionRepository repo = new SessionRepository(getApplicationContext());
        List<SessionEntity> unsynced = repo.getUnsyncedForCurrentUser();
        
        try {
            for (SessionEntity s : unsynced) {
                Log.d(TAG, "Syncing session: " + s.id);
                repo.uploadSessionSync(s);
            }
            Log.d(TAG, "Sync completed successfully.");
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Sync failed: " + e.getMessage());
            return Result.retry();
        }
    }
}