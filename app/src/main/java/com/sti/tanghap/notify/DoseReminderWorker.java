package com.sti.tanghap.notify;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sti.tanghap.domain.DoseReminder;

import java.util.concurrent.TimeUnit;

/** Periodically reminds the user when a prescribed inhaler dose is late or missed. */
public class DoseReminderWorker extends Worker {

    private static final String UNIQUE_NAME = "tanghap_dose_reminder";

    public DoseReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void schedule(Context ctx) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                DoseReminderWorker.class, 2, TimeUnit.HOURS).build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    @NonNull
    @Override
    public Result doWork() {
        DoseReminder.Result r = DoseCheck.checkToday(getApplicationContext());
        if (r.status != DoseReminder.Status.NONE) {
            NotificationHelper.show(getApplicationContext(), NotificationHelper.ID_DOSE, r.title, r.message);
        }
        return Result.success();
    }
}
