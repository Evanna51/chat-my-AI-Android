package com.example.aichat.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.aichat.AppDatabase
import com.example.aichat.ChatService
import com.example.aichat.Message
import com.example.aichat.SessionChatOptions
import com.example.aichat.SessionChatOptionsStore
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

/**
 * Coordinator for 自动对话 (proactive chat) post-processing.
 *
 * Flow per turn:
 *   1. ChatService.onSuccess passes raw content → ChatViewModel
 *   2. ChatViewModel runs [ProactiveMetaParser.extract] → cleanContent + meta
 *   3. ChatViewModel inserts Message(cleanContent) and notifies us via [onAssistantTurnFinalized]
 *   4. We:
 *      - Apply meta.split   → rewrite the just-inserted message + schedule extra inserts
 *      - Apply meta.followUp → schedule a delayed follow-up API call
 *
 * Lifecycle: created once per ChatViewModel; single instance covers one open session.
 * Follow-up timers are keyed by sessionId so a stray cross-session message can't
 * fire the wrong session.
 *
 * 预算 / 限速 (V1):
 *   - per-session 每日上限 [DAILY_PROACTIVE_BUDGET]
 *   - 同一沉默期最多 1 次 follow-up (V1; V2 提到 2)
 *   - 用户发送新消息 → cancelFollowUp() 立即取消未触发的 follow-up
 */
class ProactiveChatPlanner(
    private val context: Context,
    private val executor: ExecutorService,
    private val db: AppDatabase,
    private val chatService: ChatService,
    /** Called after split rewrite or follow-up insert; ChatViewModel reloads UI. */
    private val onMessageDbChanged: () -> Unit,
) {

    companion object {
        private const val TAG = "ProactiveChatPlanner"

        /** 同一段回复内 split 各部分的渲染间隔. */
        private const val SPLIT_INTERVAL_MS = 1500L

        /** 每个会话每天允许的主动消息数 (split + follow-up 都算入). */
        const val DAILY_PROACTIVE_BUDGET = 20

        /** Follow-up chain 最大长度 (含触发那一条). 再多就停止. */
        private const val MAX_FOLLOWUP_CHAIN = 2

        /** 历史回顾深度: 拉这么多条最近消息作为 follow-up 上下文. */
        private const val FOLLOWUP_HISTORY_LIMIT = 10
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val optionsStore = SessionChatOptionsStore(context.applicationContext)

    /** Per-session pending follow-up runnables (keyed by sessionId). */
    private val pendingFollowUps = ConcurrentHashMap<String, PendingFollowUp>()

    /** Per-session split scheduling state — for cleanup on cancel. */
    private val pendingSplitRunnables = ConcurrentHashMap<String, MutableList<Runnable>>()

    private data class PendingFollowUp(
        val runnable: Runnable,
        val intent: String,
        val chainDepth: Int,
    )

    /**
     * Called by ChatViewModel after it inserts a consolidated Message for the assistant turn.
     *
     * @param sessionId          the session
     * @param assistantId        bound assistant (may be empty)
     * @param insertedMessageId  the row id of the just-inserted Message (for split rewrite)
     * @param cleanContent       META-stripped content
     * @param meta               parsed META; may be null if model omitted / parse failed
     * @param options            session options (used for budget & enable check)
     */
    fun onAssistantTurnFinalized(
        sessionId: String,
        assistantId: String,
        insertedMessageId: Long,
        @Suppress("UNUSED_PARAMETER") cleanContent: String,
        meta: ProactiveMeta?,
        options: SessionChatOptions,
    ) {
        if (sessionId.isEmpty()) return
        if (!options.autoChatEnabled) return
        if (meta == null) return

        // 用户每发一轮, 旧的 follow-up 计数 chain 重置 (新 turn 是"刚回复用户")
        cancelFollowUp(sessionId)
        cancelPendingSplits(sessionId)

        applySplit(sessionId, assistantId, insertedMessageId, meta.split)
        scheduleFollowUp(
            sessionId = sessionId,
            assistantId = assistantId,
            followUp = meta.followUp,
            chainDepth = 1,
        )
    }

    /**
     * 用户发了新消息 → 立刻取消所有待触发的 follow-up + split.
     */
    fun cancelFollowUp(sessionId: String) {
        val pending = pendingFollowUps.remove(sessionId) ?: return
        mainHandler.removeCallbacks(pending.runnable)
    }

    fun cancelPendingSplits(sessionId: String) {
        val list = pendingSplitRunnables.remove(sessionId) ?: return
        for (r in list) mainHandler.removeCallbacks(r)
    }

    /** Stop all timers; call from ChatViewModel.onCleared. */
    fun shutdown() {
        for ((_, p) in pendingFollowUps) mainHandler.removeCallbacks(p.runnable)
        pendingFollowUps.clear()
        for ((_, list) in pendingSplitRunnables) for (r in list) mainHandler.removeCallbacks(r)
        pendingSplitRunnables.clear()
    }

    // ─────────────────────────── Split handling ───────────────────────────

    private fun applySplit(
        sessionId: String,
        assistantId: String,
        insertedMessageId: Long,
        split: List<String>?,
    ) {
        if (split == null || split.size < 2) return
        if (insertedMessageId <= 0) return

        // V1: 把第一条改写到刚插入的 row, 后续 split 作为新 row 间隔插入
        executor.execute {
            try {
                db.messageDao().updateContentAndProactiveKind(
                    insertedMessageId, split[0], 1
                )
                onMessageDbChanged()
            } catch (e: Exception) {
                Log.w(TAG, "split[0] rewrite failed", e)
            }
        }

        val list = mutableListOf<Runnable>()
        pendingSplitRunnables[sessionId] = list

        for (i in 1 until split.size) {
            val part = split[i]
            val delay = i * SPLIT_INTERVAL_MS
            val r = Runnable {
                executor.execute {
                    try {
                        // 预算检查: 跨过限额就停发后续 split (但不补偿前面已发的).
                        if (!consumeBudgetIfAllowed(sessionId)) {
                            Log.i(TAG, "split[$i] suppressed: daily budget exhausted")
                            return@execute
                        }
                        val msg = Message(sessionId, Message.ROLE_ASSISTANT, part)
                        msg.assistantId = assistantId
                        msg.proactiveKind = 1
                        // 不 stamp turnId: server 还不收 split 副本, 留给后续 schema 升级.
                        db.messageDao().insert(msg)
                        onMessageDbChanged()
                    } catch (e: Exception) {
                        Log.w(TAG, "split[$i] insert failed", e)
                    }
                }
            }
            list.add(r)
            mainHandler.postDelayed(r, delay)
        }
    }

    // ─────────────────────────── Follow-up handling ───────────────────────────

    private fun scheduleFollowUp(
        sessionId: String,
        assistantId: String,
        followUp: ProactiveFollowUp?,
        chainDepth: Int,
    ) {
        if (followUp == null) return
        if (chainDepth > MAX_FOLLOWUP_CHAIN) return
        val delayMs = followUp.afterSec.toLong() * 1000L
        val intent = followUp.intent

        val runnable = Runnable {
            // Check still scheduled (user might have sent meanwhile via cancelFollowUp removal)
            if (pendingFollowUps[sessionId] == null) return@Runnable
            pendingFollowUps.remove(sessionId)
            executor.execute {
                try {
                    if (!consumeBudgetIfAllowed(sessionId)) {
                        Log.i(TAG, "follow-up suppressed: daily budget exhausted")
                        return@execute
                    }
                    fireFollowUp(sessionId, assistantId, intent, chainDepth)
                } catch (e: Exception) {
                    Log.w(TAG, "follow-up fire failed", e)
                }
            }
        }
        pendingFollowUps[sessionId] = PendingFollowUp(runnable, intent, chainDepth)
        mainHandler.postDelayed(runnable, delayMs)
    }

    /**
     * Fires a single follow-up turn: assembles last-K-messages + 我们的 special instruction,
     * dispatches via ChatService.chat. On success, inserts Message with proactiveKind=2 and
     * recursively schedules next follow-up if model returned new META.followUp.
     *
     * Runs on executor thread (already off main).
     */
    private fun fireFollowUp(
        sessionId: String,
        assistantId: String,
        previousIntent: String,
        chainDepth: Int,
    ) {
        val opts = optionsStore.get(sessionId)
        if (!opts.autoChatEnabled) return  // 用户中途关了开关
        // 注入 system suffix: opts.systemPrompt + ProactivePromptBuilder.buildSystemSuffix()
        val effective = opts.copy(
            systemPrompt = opts.systemPrompt.trimEnd() + "\n" +
                ProactivePromptBuilder.buildSystemSuffix()
        )
        val historyDesc = try {
            db.messageDao().getLatestBySession(sessionId, FOLLOWUP_HISTORY_LIMIT)
        } catch (e: Exception) {
            Log.w(TAG, "history load failed", e)
            return
        }
        // ChatService 期望 ascending
        val historyAsc = ArrayList(historyDesc)
        historyAsc.reverse()

        val silenceSec = computeSilenceSec(historyAsc)
        val instruction = ProactivePromptBuilder.buildFollowUpInstruction(silenceSec, previousIntent)

        // ChatCallback for follow-up: persist with proactiveKind=2, then recurse if META.followUp
        val callback = object : ChatService.ChatCallback {
            private val captured = StringBuilder()

            override fun onPartial(delta: String) {
                captured.append(delta)
            }

            override fun onSuccess(content: String) {
                val raw = if (content.isNotEmpty()) content else captured.toString()
                val extract = ProactiveMetaParser.extract(raw)
                val cleaned = extract.cleanContent.trim()
                if (cleaned.isEmpty() || cleaned.equals("[SKIP]", ignoreCase = true)) {
                    Log.i(TAG, "follow-up SKIP / empty → no message")
                    // 模型仍可在 META 给新 followUp; 但 V1 我们不 chain SKIP.
                    return
                }
                executor.execute {
                    try {
                        val msg = Message(sessionId, Message.ROLE_ASSISTANT, cleaned)
                        msg.assistantId = assistantId
                        msg.proactiveKind = 2
                        // 不 stamp turnId: 同 split 一样, server 还不识别这种"主动"轮次.
                        val newId = db.messageDao().insert(msg)
                        onMessageDbChanged()

                        // 把 follow-up 自身视作一个 turn, 应用 split + 排下一次 follow-up.
                        // 注意 chainDepth+1, 防止无限链.
                        applySplit(sessionId, assistantId, newId, extract.meta?.split)
                        scheduleFollowUp(
                            sessionId, assistantId,
                            extract.meta?.followUp, chainDepth + 1
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "follow-up persist failed", e)
                    }
                }
            }

            override fun onError(message: String) {
                Log.w(TAG, "follow-up onError: $message")
            }
        }

        // 把 instruction 当作 user 发起 — ChatService.chat 把 userMessage 视为最新 user turn.
        chatService.chat(historyAsc, instruction, effective, callback)
    }

    /** 距 history 最后一条 user/assistant message 的秒数 (近似 silence 量). */
    private fun computeSilenceSec(historyAsc: List<Message>): Int {
        if (historyAsc.isEmpty()) return 60
        val lastTs = historyAsc.last().createdAt
        if (lastTs <= 0) return 60
        val diff = (System.currentTimeMillis() - lastTs) / 1000L
        return diff.coerceIn(30L, 1800L).toInt()
    }

    // ─────────────────────────── Budget ───────────────────────────

    /**
     * Consume one slot from today's budget. Returns true if allowed (and counter incremented),
     * false if exhausted. Reset on date change.
     *
     * Synchronous DB op; call from executor thread.
     */
    private fun consumeBudgetIfAllowed(sessionId: String): Boolean {
        val opts = optionsStore.get(sessionId)
        if (!opts.autoChatEnabled) return false
        val today = todayStamp()
        val current = if (opts.proactiveResetDate != today) 0 else opts.proactiveCountToday
        if (current >= DAILY_PROACTIVE_BUDGET) return false
        opts.proactiveCountToday = current + 1
        opts.proactiveResetDate = today
        optionsStore.save(sessionId, opts)
        return true
    }

    private fun todayStamp(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.YEAR) * 10000 +
            (cal.get(Calendar.MONTH) + 1) * 100 +
            cal.get(Calendar.DAY_OF_MONTH)
    }
}
