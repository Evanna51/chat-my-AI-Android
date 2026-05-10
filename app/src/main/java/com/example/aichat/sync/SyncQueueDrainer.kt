package com.example.aichat.sync

import android.content.Context
import android.util.Log
import com.example.aichat.AppDatabase
import com.example.aichat.Message

/**
 * Drains pending message rows by pushing batches to wi-chat-server.
 * Designed to be called from background threads (Worker / NetworkCallback).
 *
 * Behavior matches docs/android-sync-integration.md:
 * - INSERT OR IGNORE on server, so duplicate pushes are idempotent.
 * - 4xx (other than 401): mark per-row failed up to MAX_ATTEMPTS, no infinite retry.
 * - 5xx / network: throw, let WorkManager backoff retry.
 */
object SyncQueueDrainer {

    private const val TAG = "SyncQueueDrainer"
    private const val BATCH_SIZE = 100
    private const val MAX_ATTEMPTS = 5

    sealed class Result {
        object Disabled : Result()
        object Empty : Result()
        data class Drained(val accepted: Int, val skipped: Int, val rejected: Int) : Result()
        data class TransportError(val cause: Throwable) : Result()
    }

    /**
     * Run a single drain pass. Synchronous — caller is responsible for IO threading.
     * @throws SyncTransientException on transport / 5xx errors so callers (Worker) can retry.
     */
    @Throws(SyncTransientException::class)
    fun drain(context: Context): Result {
        val configStore = RemoteSyncConfigStore(context)
        if (!configStore.isReady()) return Result.Disabled

        val deviceId = DeviceIdProvider.get(context)
        val api = ChatServerApi(configStore.getBaseUrl(), configStore.getApiKey())
        val dao = AppDatabase.getInstance(context).messageDao()

        var totalAccepted = 0
        var totalSkipped = 0
        var totalRejected = 0
        var anyRound = false

        while (true) {
            val batch = dao.pendingSyncBatch(MAX_ATTEMPTS, BATCH_SIZE)
            if (batch.isEmpty()) break
            anyRound = true

            val request = ChatTurnRequest(
                deviceId = deviceId,
                turns = batch.map { it.toTurnDto() }
            )

            val response = try {
                api.chatTurn(request)
            } catch (e: ChatServerApi.HttpStatusException) {
                if (e.statusCode == 401) {
                    val msg = "unauthorized (api key invalid)"
                    configStore.setLastError(msg)
                    dao.markSyncFailed(batch.map { it.turnId }, System.currentTimeMillis(), msg)
                    Log.w(TAG, "401 from server, stop draining")
                    return Result.TransportError(e)
                }
                if (e.statusCode in 400..499) {
                    val msg = "4xx:${e.statusCode}"
                    configStore.setLastError(msg)
                    dao.markSyncFailed(batch.map { it.turnId }, System.currentTimeMillis(), msg)
                    Log.w(TAG, "$msg from server: ${e.responseBody}")
                    return Result.TransportError(e)
                }
                throw SyncTransientException("server ${e.statusCode}", e)
            } catch (e: Exception) {
                throw SyncTransientException(e.message ?: "transport error", e)
            }

            val accepted = response.details.filter { it.status == "accepted" || it.status == "skipped" }
            val rejected = response.details.filter { it.status == "rejected" }
            if (accepted.isNotEmpty()) dao.markSynced(accepted.map { it.id })
            if (rejected.isNotEmpty()) {
                dao.markSyncFailed(
                    rejected.map { it.id },
                    System.currentTimeMillis(),
                    rejected.firstOrNull()?.reason ?: "rejected"
                )
            }

            totalAccepted += response.ingested
            totalSkipped += response.deduped
            totalRejected += response.rejected

            if (batch.size < BATCH_SIZE) break
        }

        if (!anyRound) return Result.Empty

        configStore.setLastSyncAt(System.currentTimeMillis())
        configStore.setLastError("")
        return Result.Drained(totalAccepted, totalSkipped, totalRejected)
    }

    private fun Message.toTurnDto(): SyncTurnDto = SyncTurnDto(
        id = turnId,
        assistantId = assistantId,
        sessionId = sessionId,
        role = if (role == Message.ROLE_USER) "user" else "assistant",
        content = content,
        createdAt = if (createdAt > 0) createdAt else System.currentTimeMillis()
    )

    /** Wraps a 5xx / network error so WorkManager can retry. */
    class SyncTransientException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
