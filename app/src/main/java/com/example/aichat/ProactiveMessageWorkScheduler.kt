package com.example.aichat

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ProactiveMessageWorkScheduler {

    private const val UNIQUE_WORK_NAME = "proactive_message_poll"
    const val BACKGROUND_POLL_INTERVAL_MINUTES: Long = 10L

    @JvmStatic
    fun scheduleNext(context: Context) {
        scheduleNext(context, BACKGROUND_POLL_INTERVAL_MINUTES)
    }

    @JvmStatic
    fun scheduleNext(context: Context, delayMinutes: Long) {
        val app = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequest.Builder(ProactiveMessageWorker::class.java)
            .setInitialDelay(maxOf(1L, delayMinutes), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    @JvmStatic
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
