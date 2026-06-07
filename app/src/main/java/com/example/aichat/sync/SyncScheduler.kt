package com.example.aichat.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import java.util.concurrent.atomic.AtomicLong

/**
 * Centralizes the two non-realtime sync triggers documented in
 * docs/android-sync-integration.md:
 *
 *  1. PeriodicWorkRequest — daily drain as the safety net.
 *  2. ConnectivityManager.NetworkCallback — drain on network entry once
 *     `/api/health` confirms the home server is reachable. The same
 *     callback also flips the active [RemoteSyncConfigStore.Mode] between
 *     HOME and AWAY based on WiFi state + home reachability.
 *
 * Auto-switch rules (see [evaluateModeOnNetworkChange]):
 *   - WiFi off  →                                AWAY
 *   - WiFi on + home /api/health reachable    →  HOME
 *   - WiFi on + home /api/health unreachable  →  AWAY
 *
 * Anti-loop: only persist a mode change when the new mode differs from the
 * stored one, and gate evaluations behind a [SWITCH_COOLDOWN_MS] so flapping
 * networks can't ping-pong the mode. Fallback: if evaluation throws or the
 * config is incomplete, leave the mode untouched.
 *
 * "Realtime per-message push" is intentionally NOT implemented per the
 * project's revised plan (every message push removed in favour of
 * batched drains).
 */
object SyncScheduler {

    private const val TAG = "SyncScheduler"
    private const val PERIODIC_NAME = "wi-sync-drain-daily"
    private const val ON_DEMAND_NAME = "wi-sync-drain-once"
    private const val SWITCH_COOLDOWN_MS = 5_000L

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var registered = false
    private val lastEvalAt = AtomicLong(0L)

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
                onNetworkChanged(appContext, cm, network)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                onNetworkChanged(appContext, cm, network)
            }

            override fun onLost(network: Network) {
                // WiFi dropped to cellular / no network: force AWAY.
                ioExecutor.execute {
                    try {
                        val store = RemoteSyncConfigStore(appContext)
                        if (!store.isEnabled()) return@execute
                        switchMode(store, RemoteSyncConfigStore.Mode.AWAY, reason = "network lost")
                    } catch (e: Exception) {
                        Log.w(TAG, "onLost handler failed: ${e.message}")
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

    private fun onNetworkChanged(appContext: Context, cm: ConnectivityManager, network: Network) {
        // Coalesce bursty callbacks (onAvailable + onCapabilitiesChanged often
        // fire back-to-back) so we don't ping-pong the mode.
        val now = System.currentTimeMillis()
        val last = lastEvalAt.get()
        if (now - last < SWITCH_COOLDOWN_MS) return
        if (!lastEvalAt.compareAndSet(last, now)) return

        ioExecutor.execute {
            try {
                val store = RemoteSyncConfigStore(appContext)
                if (!store.isEnabled()) return@execute
                val onWifi = isWifi(cm, network)
                val target = evaluateModeOnNetworkChange(store, onWifi)
                if (target != null) {
                    switchMode(store, target, reason = if (onWifi) "wifi on" else "wifi off")
                }
                // Kick a drain whenever the active mode has a reachable server.
                if (store.isReady()) {
                    val api = ChatServerApi(store.getBaseUrl(), store.getApiKey(), timeoutSeconds = 3)
                    if (api.health()) {
                        Log.i(TAG, "active mode=${store.getMode().key} reachable, kicking drain worker")
                        runOnceNow(appContext)
                    } else {
                        Log.d(TAG, "active mode=${store.getMode().key} /api/health unreachable, skip drain")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "onNetworkChanged handler failed: ${e.message}")
            }
        }
    }

    /**
     * Decide the target mode without persisting it. Returns null when no
     * change is desired (e.g., the candidate equals the current mode, or
     * neither mode is configured and we want to keep the user's setting).
     *
     * Fallback policy:
     *   - If WiFi off → AWAY (always; even if away has no config, user sees
     *     an obvious "no server" state rather than us silently staying on
     *     unreachable home).
     *   - If WiFi on + home reachable → HOME.
     *   - If WiFi on + home unreachable → AWAY only if away is configured;
     *     otherwise stay on HOME (no point flipping to an empty profile).
     */
    private fun evaluateModeOnNetworkChange(
        store: RemoteSyncConfigStore,
        onWifi: Boolean
    ): RemoteSyncConfigStore.Mode? {
        val current = store.getMode()
        if (!onWifi) {
            return if (current == RemoteSyncConfigStore.Mode.AWAY) null
            else RemoteSyncConfigStore.Mode.AWAY
        }
        // WiFi on: probe home reachability.
        val homeBase = store.getBaseUrl(RemoteSyncConfigStore.Mode.HOME)
        val homeReachable = if (homeBase.isEmpty()) false else try {
            ChatServerApi(homeBase, store.getApiKey(RemoteSyncConfigStore.Mode.HOME), timeoutSeconds = 3)
                .health()
        } catch (_: Exception) { false }
        return if (homeReachable) {
            if (current == RemoteSyncConfigStore.Mode.HOME) null
            else RemoteSyncConfigStore.Mode.HOME
        } else {
            if (current == RemoteSyncConfigStore.Mode.AWAY) null
            else if (store.isModeConfigured(RemoteSyncConfigStore.Mode.AWAY))
                RemoteSyncConfigStore.Mode.AWAY
            else null
        }
    }

    private fun switchMode(
        store: RemoteSyncConfigStore,
        mode: RemoteSyncConfigStore.Mode,
        reason: String
    ) {
        val changed = store.setMode(mode)
        if (changed) {
            Log.i(TAG, "remote-service mode switched to ${mode.key} ($reason)")
        }
    }

    private fun isWifi(cm: ConnectivityManager, network: Network): Boolean {
        val caps = try { cm.getNetworkCapabilities(network) } catch (_: Exception) { null }
            ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
