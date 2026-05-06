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
import com.example.aichat.SessionChatOptionsStore
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

        /** Worker max runtime ~ 10min; 上限 chat 调用应远低于此. */
        private const val MAX_WAIT_SECONDS = 90L

        /** Follow-up history depth (与 in-process planner 对齐). */
        private const val FOLLOWUP_HISTORY_LIMIT = 10

        /** 同一沉默期最多发起的 follow-up 链长度. */
        private const val MAX_FOLLOWUP_CHAIN = 2

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
        ) {
            if (sessionId.isEmpty()) return
            val data = Data.Builder()
                .putString(KEY_SESSION_ID, sessionId)
                .putString(KEY_ASSISTANT_ID, assistantId)
                .putString(KEY_PREVIOUS_INTENT, previousIntent)
                .putInt(KEY_CHAIN_DEPTH, chainDepth)
                .putLong(KEY_LAST_USER_MESSAGE_TS, System.currentTimeMillis())
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

        if (sessionId.isEmpty()) return Result.success()
        if (chainDepth > MAX_FOLLOWUP_CHAIN) {
            Log.i(TAG, "chain too deep ($chainDepth); aborting")
            return Result.success()
        }

        val ctx = applicationContext
        val optsStore = SessionChatOptionsStore(ctx)
        val opts = optsStore.get(sessionId)
        if (!opts.autoChatEnabled) {
            Log.i(TAG, "autoChat disabled for $sessionId; skip")
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

        // 注入 system 后缀, 让 follow-up 这次模型也走 META 协议.
        val effective = opts.copy(
            systemPrompt = opts.systemPrompt.trimEnd() + "\n" +
                ProactivePromptBuilder.buildSystemSuffix()
        )

        val historyDesc = try {
            db.messageDao().getLatestBySession(sessionId, FOLLOWUP_HISTORY_LIMIT)
        } catch (e: Exception) {
            Log.w(TAG, "history load failed", e)
            return Result.failure()
        }
        val historyAsc = ArrayList(historyDesc).also { it.reverse() }
        val silenceSec = computeSilenceSec(historyAsc)
        val instruction = ProactivePromptBuilder.buildFollowUpInstruction(silenceSec, previousIntent)

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

        // META 由 ChatService.onSuccess 回调前已经 strip 了; raw 即 cleanContent.
        // 但 follow-up 也可能输出 META, 因为 Worker 的 callback 没经过 ChatService 的 onSuccess
        // 抽取前置链路 (我们传的是空 callback). 实际 ChatService 在 onSuccess 之前已抽过, 此处 raw 就是 cleanContent.
        val cleaned = raw.trim()
        if (cleaned.isEmpty() || cleaned.equals("[SKIP]", ignoreCase = true)) {
            Log.i(TAG, "follow-up SKIP / empty")
            return Result.success()
        }

        // 我们仍要再 parse 一次, 以便拿到 followUp 决策来排下一轮.
        // (ChatService 已经 strip; 但若 strip 后还有未分离的 META 它仍 idempotent.)
        val extract = ProactiveMetaParser.extract(cleaned)
        val finalContent = extract.cleanContent.ifEmpty { cleaned }

        // 持久化 follow-up message
        try {
            val msg = Message(sessionId, Message.ROLE_ASSISTANT, finalContent)
            msg.assistantId = assistantId
            msg.proactiveKind = 2
            db.messageDao().insert(msg)
        } catch (e: Exception) {
            Log.w(TAG, "persist failed", e)
            return Result.retry()
        }

        // Notification (deep-link 到该 session)
        try {
            val assistantName = if (assistantId.isNotEmpty())
                MyAssistantStore(ctx).getById(assistantId)?.name?.takeIf { it.isNotBlank() }
                else null
            ProactiveMessageNotifier(ctx).notifyMessage(
                messageId = "proactive_${sessionId}_${System.currentTimeMillis()}",
                title = assistantName ?: "新消息",
                body = finalContent,
                sessionId = sessionId,
                assistantId = assistantId
            )
        } catch (e: Exception) {
            Log.w(TAG, "notify failed", e)
        }

        // chain
        val nextFollow = extract.meta?.followUp
        if (nextFollow != null && chainDepth + 1 <= MAX_FOLLOWUP_CHAIN) {
            schedule(
                ctx,
                sessionId,
                assistantId,
                nextFollow.intent,
                nextFollow.afterSec,
                chainDepth + 1
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
}
