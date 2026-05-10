package com.example.aichat.proactive

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.aichat.AppDatabase
import com.example.aichat.ChatService
import com.example.aichat.Message
import com.example.aichat.MyAssistantStore
import com.example.aichat.ProactiveMessageNotifier
import com.example.aichat.RelationshipStateStore
import com.example.aichat.SessionChatOptionsStore
import com.example.aichat.chat.ChatTimeContext
import com.example.aichat.chat.ProactiveBudget
import com.example.aichat.chat.ProactiveMetaParser
import com.example.aichat.chat.ProactivePromptBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Background-fired follow-up turn for 自动对话.
 *
 * Triggered by [ProactiveChatPlanner] via [WorkManager] with `afterSec` initialDelay.
 * Runs even when the chat Activity is backgrounded or destroyed — that's the V2
 * upgrade over V1's mainHandler-based scheduling.
 *
 * Lifecycle:
 *   1. Verify auto-chat still on for sessionId
 *   2. Consume budget via [ProactiveBudget] (atomic)
 *   3. Build follow-up prompt via [ProactivePromptBuilder.buildFollowUpInstruction]
 *   4. Synchronously wait on [ChatService.chat] via [CountDownLatch]
 *   5. On non-[SKIP] response: persist as Message(role=ASSISTANT, proactiveKind=2)
 *      and fire a notification via [ProactiveMessageNotifier]
 *   6. If META.followUp present, enqueue another worker (chained)
 *
 * Cancellation: planner / Activity sends new user message → [cancelFor] cancels
 * pending work by tag = "proactive_followup_<sessionId>".
 */
class ProactiveFollowUpWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "ProactiveFollowUpWorker"

        const val KEY_SESSION_ID = "session_id"
        const val KEY_ASSISTANT_ID = "assistant_id"
        const val KEY_PREVIOUS_INTENT = "previous_intent"
        const val KEY_CHAIN_DEPTH = "chain_depth"
        const val KEY_LAST_USER_MESSAGE_TS = "last_user_msg_ts"
        const val KEY_CHAIN_START_EPOCH_MS = "chain_start_epoch_ms"

        /** Worker max runtime ~ 10min; 上限 chat 调用应远低于此. */
        private const val MAX_WAIT_SECONDS = 90L

        /** 单沉默期 follow-up 链最长存活时间. 超过后即使模型仍想继续也强制终止.
         *  防止链在后台长期驻留（如用户睡觉后设备解 Doze，积压的 job 一次打出来）. */
        private const val CHAIN_WINDOW_MS = 2 * 60 * 60 * 1000L  // 2 小时

        /** Follow-up history depth (与 in-process planner 对齐). */
        private const val FOLLOWUP_HISTORY_LIMIT = 10

        fun tagFor(sessionId: String): String = "proactive_followup_$sessionId"

        /**
         * Schedule a follow-up worker for the given session after `delaySec` seconds.
         *
         * Uses ExistingWorkPolicy.REPLACE: any previously scheduled follow-up for the
         * same sessionId is cancelled (e.g. user kept typing → AI keeps re-evaluating).
         */
        fun schedule(
            context: Context,
            sessionId: String,
            assistantId: String,
            previousIntent: String,
            delaySec: Int,
            chainDepth: Int,
            chainStartEpochMs: Long = System.currentTimeMillis(),
        ) {
            if (sessionId.isEmpty()) return
            val data = Data.Builder()
                .putString(KEY_SESSION_ID, sessionId)
                .putString(KEY_ASSISTANT_ID, assistantId)
                .putString(KEY_PREVIOUS_INTENT, previousIntent)
                .putInt(KEY_CHAIN_DEPTH, chainDepth)
                .putLong(KEY_LAST_USER_MESSAGE_TS, System.currentTimeMillis())
                .putLong(KEY_CHAIN_START_EPOCH_MS, chainStartEpochMs)
                .build()
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<ProactiveFollowUpWorker>()
                .setInitialDelay(delaySec.toLong(), TimeUnit.SECONDS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30L, TimeUnit.SECONDS)
                .setInputData(data)
                .addTag(tagFor(sessionId))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    "proactive_followup_unique_$sessionId",
                    ExistingWorkPolicy.REPLACE,
                    req
                )
        }

        /** Cancel any pending follow-up for this session (called on user send / toggle off). */
        fun cancelFor(context: Context, sessionId: String) {
            if (sessionId.isEmpty()) return
            try {
                WorkManager.getInstance(context.applicationContext)
                    .cancelUniqueWork("proactive_followup_unique_$sessionId")
            } catch (_: Exception) {}
        }
    }

    override fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID).orEmpty()
        val assistantId = inputData.getString(KEY_ASSISTANT_ID).orEmpty()
        val previousIntent = inputData.getString(KEY_PREVIOUS_INTENT).orEmpty()
        val chainDepth = inputData.getInt(KEY_CHAIN_DEPTH, 1)
        val lastUserTsAtSchedule = inputData.getLong(KEY_LAST_USER_MESSAGE_TS, 0L)
        val chainStartEpochMs = inputData.getLong(KEY_CHAIN_START_EPOCH_MS, System.currentTimeMillis())

        if (sessionId.isEmpty()) return Result.success()
        if (chainDepth > ProactiveBudget.HARD_FOLLOWUP_CHAIN_MAX) {
            Log.i(TAG, "chain hard ceiling ($chainDepth); aborting")
            return Result.success()
        }
        val chainAgeMs = System.currentTimeMillis() - chainStartEpochMs
        if (chainAgeMs > CHAIN_WINDOW_MS) {
            Log.i(TAG, "chain window expired (${chainAgeMs / 60_000}min > 120min); aborting")
            return Result.success()
        }

        val ctx = applicationContext
        val optsStore = SessionChatOptionsStore(ctx)
        val opts = optsStore.get(sessionId)
        if (!opts.autoChatEnabled) {
            Log.i(TAG, "autoChat disabled for $sessionId; skip")
            return Result.success()
        }

        // 决定本轮用哪个模型: 云端 chain ≤ CLOUD_FOLLOWUP_CHAIN_MAX, 之后切本地 fallback.
        // 没有本地 provider 就此停链, 不再继续.
        val effectiveModelKey: String?
        val tier: String
        if (chainDepth <= ProactiveBudget.CLOUD_FOLLOWUP_CHAIN_MAX) {
            effectiveModelKey = opts.modelKey.takeIf { it.isNotEmpty() }
            tier = "cloud"
        } else {
            val local = ProactiveBudget.findLocalFallbackModelKey(ctx)
            if (local == null) {
                Log.i(TAG, "chain $chainDepth > cloud max but no local fallback; stop")
                return Result.success()
            }
            effectiveModelKey = local
            tier = "local"
            Log.i(TAG, "chain $chainDepth → switching to local provider (modelKey=$local)")
        }
        if (effectiveModelKey.isNullOrEmpty()) {
            Log.w(TAG, "no usable modelKey; skip")
            return Result.success()
        }

        // Bail out if the user wrote something AFTER scheduling — they're back, no need to nudge.
        val db = AppDatabase.getInstance(ctx)
        try {
            val recent = db.messageDao().getLatestBySession(sessionId, 5)
            // recent 是 desc 顺序; 找最新的 user role 行
            val latestUserTs = recent.firstOrNull { it.role == Message.ROLE_USER }?.createdAt ?: 0L
            if (latestUserTs > lastUserTsAtSchedule && lastUserTsAtSchedule > 0) {
                Log.i(TAG, "user replied after schedule; cancel follow-up")
                return Result.success()
            }
        } catch (e: Exception) {
            Log.w(TAG, "user-reply check failed; proceeding", e)
        }

        if (!ProactiveBudget.consumeIfAllowed(ctx, sessionId)) {
            Log.i(TAG, "budget exhausted for $sessionId; skip")
            return Result.success()
        }

        // 取关系状态, 用于 prompt 调制 (亲密度高 → 阈值放宽).
        val closeness = try {
            RelationshipStateStore(ctx).getCached(assistantId)?.closeness?.takeIf { it > 0 }
        } catch (_: Exception) { null }
        val relationshipHint = try {
            RelationshipStateStore(ctx).buildPromptHintForAssistant(assistantId).orEmpty()
        } catch (_: Exception) { "" }

        // 注入 system 后缀: 时间 (角色场景永远) + 关系状态 + META 协议指令.
        // 时间 prefix 让模型知道现在是什么时候 (深夜还是周末等), 影响主动性判断.
        val timePrefix = ChatTimeContext.describeNow()
        val mergedSystemPrompt = buildString {
            append(timePrefix).append('\n')
            if (relationshipHint.isNotEmpty()) append(relationshipHint).append('\n')
            val origin = opts.systemPrompt.trim()
            if (origin.isNotEmpty()) append(origin).append('\n')
            append(ProactivePromptBuilder.buildSystemSuffix(closeness))
        }
        val effective = opts.copy(
            modelKey = effectiveModelKey,
            systemPrompt = mergedSystemPrompt
        )

        val historyDesc = try {
            db.messageDao().getLatestBySession(sessionId, FOLLOWUP_HISTORY_LIMIT)
        } catch (e: Exception) {
            Log.w(TAG, "history load failed", e)
            return Result.failure()
        }
        val historyAsc = ArrayList(historyDesc).also { it.reverse() }
        val silenceSec = computeSilenceSec(historyAsc)
        val budgetUsed = if (opts.proactiveResetDate == ProactiveBudget.todayStamp())
            opts.proactiveCountToday else 0
        val budgetLimit = ProactiveBudget.effectiveLimit(opts.proactiveDailyBudget)
        val instruction = ProactivePromptBuilder.buildFollowUpInstruction(
            silenceSec = silenceSec,
            previousIntent = previousIntent,
            chainDepth = chainDepth,
            cloudChainMax = ProactiveBudget.CLOUD_FOLLOWUP_CHAIN_MAX,
            hardChainMax = ProactiveBudget.HARD_FOLLOWUP_CHAIN_MAX,
            tier = tier,
            budgetUsed = budgetUsed,
            budgetLimit = budgetLimit,
            closeness = closeness,
        )

        // Synchronously fire chat call.
        val chatService = ChatService(ctx)
        val latch = CountDownLatch(1)
        val resultRef = arrayOfNulls<String>(1)
        val errorRef = arrayOfNulls<String>(1)

        chatService.chat(historyAsc, instruction, effective, object : ChatService.ChatCallback {
            override fun onSuccess(content: String) {
                resultRef[0] = content
                latch.countDown()
            }
            override fun onError(message: String) {
                errorRef[0] = message
                latch.countDown()
            }
            override fun onCancelled() {
                latch.countDown()
            }
        })

        val finished = try {
            latch.await(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "interrupted", e)
            return Result.retry()
        }
        if (!finished) {
            Log.w(TAG, "chat call timed out after ${MAX_WAIT_SECONDS}s")
            return Result.retry()
        }
        if (errorRef[0] != null) {
            Log.w(TAG, "chat onError: ${errorRef[0]}")
            return Result.retry()
        }
        val raw = resultRef[0].orEmpty()
        if (raw.isEmpty()) return Result.success()

        // ChatService 已在 onSuccess 之前 strip 过 META, raw 即 cleanContent.
        val cleaned = raw.trim()
        // [SKIP] 检测: 容忍模型加 backtick / 标点 (e.g. `[SKIP]`, "[SKIP].", "[skip]!")
        if (cleaned.isEmpty() || isSkipResponse(cleaned)) {
            Log.i(TAG, "follow-up SKIP / empty")
            return Result.success()
        }

        // 我们仍要再 parse 一次, 以便拿到 followUp / split 决策.
        // (ChatService 已经 strip; 但若 strip 后还有未分离的 META 它仍 idempotent.)
        val extract = ProactiveMetaParser.extract(cleaned)
        val finalContent = extract.cleanContent.ifEmpty { cleaned }
        val splitParts = extract.meta?.split?.takeIf { it.size >= 2 }

        val assistantName = if (assistantId.isNotEmpty())
            try { MyAssistantStore(ctx).getById(assistantId)?.name?.takeIf { it.isNotBlank() } }
            catch (_: Exception) { null }
        else null

        if (splitParts != null) {
            // follow-up split: 逐段延迟入库 + 通知, 模拟真人分条发消息节奏.
            // Worker 在后台线程, 直接 Thread.sleep 做段间延迟（无 Handler/UI 依赖）.
            for ((i, part) in splitParts.withIndex()) {
                if (i > 0) {
                    val delay = splitPartDelayMs(splitParts, i)
                    try { Thread.sleep(delay) } catch (_: InterruptedException) { break }
                }
                persistAndNotifyPart(
                    sessionId, assistantId, part, assistantName, ctx, db,
                    isRetryWorthy = i == 0
                ).also { if (it == Result.retry()) return Result.retry() }
            }
        } else {
            persistAndNotifyPart(sessionId, assistantId, finalContent, assistantName, ctx, db,
                isRetryWorthy = true)
                .also { if (it == Result.retry()) return Result.retry() }
        }

        // chain. AI 的 META.autoStop=true 是硬刹车, 即便 followUp 非 null 也不再排.
        val autoStop = extract.meta?.autoStop == true
        val nextFollow = extract.meta?.followUp
        if (autoStop) {
            Log.i(TAG, "model emitted autoStop=true; chain ends at $chainDepth")
        } else if (nextFollow != null && chainDepth + 1 <= ProactiveBudget.HARD_FOLLOWUP_CHAIN_MAX) {
            schedule(
                ctx,
                sessionId,
                assistantId,
                nextFollow.intent,
                nextFollow.afterSec,
                chainDepth + 1,
                chainStartEpochMs,
            )
        }
        return Result.success()
    }

    private fun computeSilenceSec(historyAsc: List<Message>): Int {
        if (historyAsc.isEmpty()) return 60
        val lastTs = historyAsc.last().createdAt
        if (lastTs <= 0) return 60
        val diff = (System.currentTimeMillis() - lastTs) / 1000L
        return diff.coerceIn(30L, 1800L).toInt()
    }

    /**
     * 容错地识别 "AI 说不发了" 的标记. 模型常见包装:
     *   `[SKIP]`  → backtick 包
     *   [SKIP]   → 标准
     *   [skip]   → 小写
     *   [SKIP].  → 句号尾巴
     *   SKIP     → 不带括号
     */
    private fun isSkipResponse(s: String): Boolean {
        if (s.length > 30) return false
        val normalized = s.trim()
            .trim('`', '"', '\'', '\u201C', '\u201D', '\u2018', '\u2019')
            .trim()
            .trimEnd('.', '。', '!', '！', '?', '？')
            .trim()
            .lowercase(java.util.Locale.ROOT)
        return normalized == "[skip]" || normalized == "skip" ||
            normalized == "(skip)" || normalized == "<skip>"
    }

    /** 入库一条 follow-up 消息 + 触发通知. 返回 retry() 表示调用方应重试. */
    private fun persistAndNotifyPart(
        sessionId: String,
        assistantId: String,
        content: String,
        assistantName: String?,
        ctx: android.content.Context,
        db: AppDatabase,
        isRetryWorthy: Boolean,
    ): Result {
        try {
            val msg = Message(sessionId, Message.ROLE_ASSISTANT, content)
            msg.assistantId = assistantId
            msg.proactiveKind = 2
            msg.synced = 1
            db.messageDao().insert(msg)
        } catch (e: Exception) {
            Log.w(TAG, "persist failed", e)
            return if (isRetryWorthy) Result.retry() else Result.success()
        }
        try {
            ProactiveMessageNotifier(ctx).notifyMessage(
                messageId = "proactive_${sessionId}_${System.currentTimeMillis()}",
                title = assistantName ?: "新消息",
                body = content,
                sessionId = sessionId,
                assistantId = assistantId,
            )
        } catch (e: Exception) {
            Log.w(TAG, "notify failed", e)
        }
        return Result.success()
    }

    /**
     * 和 ProactiveChatPlanner.computeSplitDelayMs 相同的累积逻辑:
     * 每段延迟 = clamp(MIN + perChar * len, MAX)，前一段的延迟累加给后续段.
     */
    private fun splitPartDelayMs(parts: List<String>, idx: Int): Long {
        val minMs = 2500L
        val maxMs = 8000L
        val perChar = 80L
        var cumulative = 0L
        for (i in 0 until idx) {
            val len = parts.getOrNull(i)?.length ?: 0
            cumulative += (minMs + perChar * len).coerceAtMost(maxMs)
        }
        return cumulative
    }
}
