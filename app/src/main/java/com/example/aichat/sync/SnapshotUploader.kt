package com.example.aichat.sync

import android.content.Context
import android.util.Log
import com.example.aichat.AppDatabase
import com.example.aichat.Message
import com.example.aichat.MyAssistantStore

/**
 * "一次性同步" 入口: 把 assistants 元数据 + 第一批 turns 通过 `/api/sync/snapshot` 推到 server,
 * 剩余 turns 复用 [SyncQueueDrainer] 的普通 push 通道分批跟进.
 *
 * 与 [SyncQueueDrainer] 的区别:
 *   - drainer: 周期 / 触发型, 只发 turns
 *   - snapshot: 用户手动触发, 第一批携带 assistant 元数据
 *
 * 调用方需保证 [HistoryBackfiller.backfill] 已先执行, 这样老消息才有 turnId 进入 pending 队列.
 */
object SnapshotUploader {

    private const val TAG = "SnapshotUploader"
    private const val SNAPSHOT_BATCH = 100
    private const val SNAPSHOT_TIMEOUT_SECONDS = 60L

    sealed class Result {
        object Disabled : Result()
        object Empty : Result()
        data class Done(
            val assistantsCount: Int,
            val accepted: Int,
            val skipped: Int,
            val rejected: Int
        ) : Result()
        data class TransportError(val cause: Throwable) : Result()
    }

    fun upload(context: Context): Result {
        val configStore = RemoteSyncConfigStore(context)
        if (!configStore.isReady()) return Result.Disabled

        val dao = AppDatabase.getInstance(context).messageDao()
        val firstBatch = dao.pendingSyncBatch(maxAttempts = Int.MAX_VALUE, limit = SNAPSHOT_BATCH)
        if (firstBatch.isEmpty()) return Result.Empty

        val deviceId = DeviceIdProvider.get(context)
        val assistantIds = firstBatch.map { it.assistantId }.filter { it.isNotEmpty() }.toSet()
        val assistants = buildAssistantSnapshots(context, assistantIds)

        val api = ChatServerApi(
            configStore.getBaseUrl(),
            configStore.getApiKey(),
            timeoutSeconds = SNAPSHOT_TIMEOUT_SECONDS
        )

        val request = SnapshotPushRequest(
            deviceId = deviceId,
            assistants = assistants,
            turns = firstBatch.map { it.toTurnDto() }
        )

        val response = try {
            api.snapshotPush(request)
        } catch (e: ChatServerApi.HttpStatusException) {
            val msg = "snapshot ${e.statusCode}"
            configStore.setLastError(msg)
            dao.markSyncFailed(firstBatch.map { it.turnId }, System.currentTimeMillis(), msg)
            Log.w(TAG, "$msg: ${e.responseBody}")
            return Result.TransportError(e)
        } catch (e: Exception) {
            configStore.setLastError(e.message ?: "transport error")
            return Result.TransportError(e)
        }

        applyPushDetails(context, response)

        // 推完第一批后, 剩余 pending 复用普通 drain 通道分批跟进
        var totalAccepted = response.accepted
        var totalSkipped = response.skipped
        var totalRejected = response.rejected
        try {
            when (val tail = SyncQueueDrainer.drain(context)) {
                is SyncQueueDrainer.Result.Drained -> {
                    totalAccepted += tail.accepted
                    totalSkipped += tail.skipped
                    totalRejected += tail.rejected
                }
                is SyncQueueDrainer.Result.TransportError -> {
                    return Result.TransportError(tail.cause)
                }
                else -> Unit
            }
        } catch (e: SyncQueueDrainer.SyncTransientException) {
            return Result.TransportError(e)
        }

        configStore.setLastSyncAt(System.currentTimeMillis())
        configStore.setLastError("")
        return Result.Done(assistants.size, totalAccepted, totalSkipped, totalRejected)
    }

    private fun applyPushDetails(context: Context, response: SyncPushResponse) {
        val dao = AppDatabase.getInstance(context).messageDao()
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
    }

    private fun buildAssistantSnapshots(
        context: Context,
        assistantIds: Set<String>
    ): List<AssistantSnapshotDto> {
        if (assistantIds.isEmpty()) return emptyList()
        val store = MyAssistantStore(context)
        return assistantIds.map { aid ->
            val real = store.getById(aid)
            if (real != null) {
                val systemPrompt = real.options?.systemPrompt?.takeIf { it.isNotEmpty() }
                    ?: real.prompt
                AssistantSnapshotDto(
                    assistantId = aid,
                    characterName = real.name,
                    characterBackground = systemPrompt,
                    allowAutoLife = real.allowAutoLife,
                    allowProactiveMessage = real.allowProactiveMessage
                )
            } else {
                // fallback / 已删除的 assistant: 用 id 做名字, 其它字段保守默认
                AssistantSnapshotDto(
                    assistantId = aid,
                    characterName = aid,
                    characterBackground = "",
                    allowAutoLife = false,
                    allowProactiveMessage = false
                )
            }
        }
    }

    private fun Message.toTurnDto(): SyncTurnDto = SyncTurnDto(
        id = turnId,
        assistantId = assistantId,
        sessionId = sessionId,
        role = if (role == Message.ROLE_USER) "user" else "assistant",
        content = content,
        createdAt = if (createdAt > 0) createdAt else System.currentTimeMillis()
    )
}
