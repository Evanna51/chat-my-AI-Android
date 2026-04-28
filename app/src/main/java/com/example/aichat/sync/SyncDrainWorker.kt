package com.example.aichat.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodic + on-demand drain entry. Errors classified as transient
 * (5xx / network) trigger WorkManager backoff via Result.retry().
 */
class SyncDrainWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            when (val outcome = SyncQueueDrainer.drain(applicationContext)) {
                is SyncQueueDrainer.Result.Drained -> {
                    Log.i(TAG, "drained accepted=${outcome.accepted} skipped=${outcome.skipped} rejected=${outcome.rejected}")
                    Result.success()
                }
                is SyncQueueDrainer.Result.Empty -> Result.success()
                is SyncQueueDrainer.Result.Disabled -> Result.success()
                is SyncQueueDrainer.Result.TransportError -> Result.success() // 4xx already logged; don't retry
            }
        } catch (e: SyncQueueDrainer.SyncTransientException) {
            Log.w(TAG, "transient drain failure, will retry: ${e.message}")
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "drain failed unexpectedly", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "SyncDrainWorker"
    }
}
