package com.example.aichat.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Centralizes the two non-realtime sync triggers documented in
 * docs/android-sync-integration.md:
 *
 *  1. PeriodicWorkRequest — daily drain as the safety net.
 *  2. ConnectivityManager.NetworkCallback — drain on network entry once
 *     `/api/health` confirms the home server is reachable.
 *
 * "Realtime per-message push" is intentionally NOT implemented per the
 * project's revised plan (every message push removed in favour of
 * batched drains).
 */
object SyncScheduler {

    private const val TAG = "SyncScheduler"
    private const val PERIODIC_NAME = "wi-sync-drain-daily"
    private const val ON_DEMAND_NAME = "wi-sync-drain-once"

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var registered = false

    /**
     * Idempotent: safe to call from Application.onCreate. Schedules the
     * periodic worker (KEEP existing) and registers the network callback
     * (only once per process).
     */
    fun start(context: Context) {
        schedulePeriodic(context)
        if (!registered) {
            registerNetworkCallback(context.applicationContext)
            registered = true
        }
    }

    /**
     * Cancel both the periodic worker and any pending on-demand work.
     * Used when user toggles remote sync off in settings.
     */
    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(ON_DEMAND_NAME)
    }

    /** User-pressed "立即同步" button. Returns immediately, drains in WorkManager. */
    fun runOnceNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncDrainWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ON_DEMAND_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    private fun schedulePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncDrainWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun registerNetworkCallback(appContext: Context) {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                ioExecutor.execute {
                    try {
                        val store = RemoteSyncConfigStore(appContext)
                        if (!store.isReady()) return@execute
                        val api = ChatServerApi(store.getBaseUrl(), store.getApiKey(), timeoutSeconds = 3)
                        if (!api.health()) {
                            Log.d(TAG, "/api/health unreachable on network ${network}, skip")
                            return@execute
                        }
                        Log.i(TAG, "home server reachable, kicking drain worker")
                        runOnceNow(appContext)
                    } catch (e: Exception) {
                        Log.w(TAG, "onAvailable handler failed: ${e.message}")
                    }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "registerDefaultNetworkCallback denied: ${e.message}")
        }
    }
}
