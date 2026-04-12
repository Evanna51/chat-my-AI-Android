package com.example.aichat

import android.content.Context
import androidx.annotation.NonNull
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProactiveMessageWorker(
    @NonNull context: Context,
    @NonNull params: WorkerParameters
) : Worker(context, params) {

    @NonNull
    override fun doWork(): Result {
        val app = applicationContext
        val latch = CountDownLatch(1)
        ProactiveMessageSyncManager(app).syncOnce(object : ProactiveMessageSyncManager.SyncCallback {
            override fun onComplete() {
                latch.countDown()
            }
        })
        try {
            latch.await(60, TimeUnit.SECONDS)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        ProactiveMessageWorkScheduler.scheduleNext(app)
        return Result.success()
    }
}
