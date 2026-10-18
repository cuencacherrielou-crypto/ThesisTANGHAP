package com.sti.tanghap.sync;
import android.content.Context;
import androidx.work.*;
import java.util.concurrent.TimeUnit;
public class SyncScheduler {
    public static void enqueueOneTimeSync(Context ctx) {
        OneTimeWorkRequest req =
                new OneTimeWorkRequest.Builder(SyncWorker.class)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build())
                        .build();
        WorkManager.getInstance(ctx).enqueue(req);
    }
    public static void schedulePeriodicSync(Context ctx) {
        PeriodicWorkRequest req =
                new PeriodicWorkRequest.Builder(SyncWorker.class, 6, TimeUnit.HOURS)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build())
                        .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "tanghap_periodic_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                req
        );
    }
}