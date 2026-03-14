package com.example.aichat;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ProactiveMessageWorker extends Worker {
    public ProactiveMessageWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context app = getApplicationContext();
        CountDownLatch latch = new CountDownLatch(1);
        new ProactiveMessageSyncManager(app).syncOnce(new ProactiveMessageSyncManager.SyncCallback() {
            @Override
            public void onComplete() {
                latch.countDown();
            }
        });
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        ProactiveMessageWorkScheduler.scheduleNext(app);
        return Result.success();
    }
}
