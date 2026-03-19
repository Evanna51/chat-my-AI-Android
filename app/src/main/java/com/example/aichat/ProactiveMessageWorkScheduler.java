package com.example.aichat;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public final class ProactiveMessageWorkScheduler {
    private static final String UNIQUE_WORK_NAME = "proactive_message_poll";
    public static final long BACKGROUND_POLL_INTERVAL_MINUTES = 10L;

    private ProactiveMessageWorkScheduler() {}

    public static void scheduleNext(Context context) {
        scheduleNext(context, BACKGROUND_POLL_INTERVAL_MINUTES);
    }

    public static void scheduleNext(Context context, long delayMinutes) {
        Context app = context.getApplicationContext();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ProactiveMessageWorker.class)
                .setInitialDelay(Math.max(1L, delayMinutes), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(app).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
        );
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(UNIQUE_WORK_NAME);
    }
}
