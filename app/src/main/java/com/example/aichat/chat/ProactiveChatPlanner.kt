package com.example.aichat.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.aichat.AppDatabase
import com.example.aichat.Message
import com.example.aichat.MyAssistantStore
import com.example.aichat.SessionChatOptions
import com.example.aichat.proactive.ProactiveFollowUpWorker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

/**
 * Coordinator for 自动对话 (proactive chat) post-processing — V2.
 *
 * V2 changes vs V1:
 *   - Follow-up scheduling moved to [ProactiveFollowUpWorker] (WorkManager) so
 *     it fires regardless of Activity/process state. mainHandler is now only used
 *     for the in-session split typing animation.
 *   - Replaced `onMessageDbChanged → loadMessages` reload with finer-grained
 *     [onMessageReplaced] / [onMessageAppended] events that the ViewModel turns
 *     into incremental LiveData updates (no full DB re-read per split part).
 *   - Daily budget centralised in [ProactiveBudget].
 *
 * Flow per turn:
 *   1. ChatService.onSuccess passes META-stripped content → ChatViewModel
 *   2. ChatViewModel inserts Message(cleanContent) and notifies us via
 *      [onAssistantTurnFinalized]
 *   3. We:
 *      - Apply meta.split   → rewrite the just-inserted message + schedule extra inserts
 *      - Apply meta.followUp → schedule a [ProactiveFollowUpWorker] job
 */
class ProactiveChatPlanner(
    private val context: Context,
    private val executor: ExecutorService,
    private val db: AppDatabase,
    /** Called when a message row was rewritten (split[0] in-place rewrite). */
    private val onMessageReplaced: (rowId: Long, newContent: String) -> Unit,
    /** Called when a fresh row was inserted (split[1..N] or follow-up). */
    private val onMessageAppended: (msg: Message) -> Unit,
) {

    companion object {
        private const val TAG = "ProactiveChatPlanner"

        /** Split parts 间的最小间隔 (固定 "对方读 + 重新打字" 缓冲). */
        private const val SPLIT_MIN_INTERVAL_MS = 2500L

        /** 单段最长间隔, 防止 AI 写一大段然后等很久. */
        private const val SPLIT_MAX_INTERVAL_MS = 8000L

        /** 每个字符模拟打字时长. 中文 ~80ms 接近真人. */
        private const val SPLIT_PER_CHAR_MS = 80L
    }

    /**
     * 第 i 段 (i ≥ 1) 应该在 split[0] 渲染完之后多久出现.
     * 累积逻辑: split[1] 等 split[0] 长度 * perChar; split[2] 等 split[0] + split[1].
     * 每段都被 clamp 到 [MIN, MAX], 保证既不太快也不太长.
     */
    private fun computeSplitDelayMs(parts: List<String>, idx: Int): Long {
        if (idx <= 0) return 0L
        var cumulative = 0L
        for (i in 0 until idx) {
            val len = parts.getOrNull(i)?.length ?: 0
            val one = (SPLIT_MIN_INTERVAL_MS + SPLIT_PER_CHAR_MS * len)
                .coerceAtMost(SPLIT_MAX_INTERVAL_MS)
            cumulative += one
        }
        return cumulative
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Per-session split scheduling state — for cleanup on cancel. */
    private val pendingSplitRunnables = ConcurrentHashMap<String, MutableList<Runnable>>()

    /**
     * Called by ChatViewModel after it inserts a consolidated Message for the assistant turn.
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

        cancelFollowUp(sessionId)
        cancelPendingSplits(sessionId)

        applySplit(sessionId, assistantId, insertedMessageId, meta.split)
        // autoStop 是硬刹车: 即便 followUp 非 null, 模型已声明本次不再追问.
        if (meta.autoStop) {
            Log.i(TAG, "model emitted autoStop=true on user-driven turn; no follow-up scheduled")
            return
        }
        // 模型给了 followUp 就用; 没给的话, 如果角色允许主动消息, 注入默认兜底 followUp.
        val effectiveFollowUp = meta.followUp ?: buildFallbackFollowUp(assistantId)
        scheduleFollowUp(
            sessionId = sessionId,
            assistantId = assistantId,
            followUp = effectiveFollowUp,
            chainDepth = 1,
        )
    }

    /**
     * 兜底 follow-up: 当模型未输出 followUp 且角色有 allowProactiveMessage 时,
     * 注入一个低优先级的默认追问, 避免 follow-up 链完全断裂.
     */
    private fun buildFallbackFollowUp(assistantId: String): ProactiveFollowUp? {
        if (assistantId.isEmpty()) return null
        return try {
            val assistant = MyAssistantStore(context).getById(assistantId)
            if (assistant?.allowProactiveMessage == true) {
                ProactiveFollowUp(afterSec = 180, intent = "关心对方近况")
            } else null
        } catch (_: Exception) { null }
    }

    /**
     * 用户发了新消息 → 立刻取消所有待触发的 follow-up + split.
     */
    fun cancelFollowUp(sessionId: String) {
        ProactiveFollowUpWorker.cancelFor(context, sessionId)
    }

    fun cancelPendingSplits(sessionId: String) {
        val list = pendingSplitRunnables.remove(sessionId) ?: return
        for (r in list) mainHandler.removeCallbacks(r)
    }

    /** Stop all timers; call from ChatViewModel.onCleared. */
    fun shutdown() {
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
                onMessageReplaced(insertedMessageId, split[0])
            } catch (e: Exception) {
                Log.w(TAG, "split[0] rewrite failed", e)
            }
        }

        val list = mutableListOf<Runnable>()
        pendingSplitRunnables[sessionId] = list

        for (i in 1 until split.size) {
            val part = split[i]
            val delay = computeSplitDelayMs(split, i)
            val r = Runnable {
                executor.execute {
                    try {
                        // split 是对同一条回复的拆分显示, 不消耗 follow-up 每日预算
                        val msg = Message(sessionId, Message.ROLE_ASSISTANT, part)
                        msg.assistantId = assistantId
                        msg.proactiveKind = 1
                        // turnId 用 Message 构造的默认 UuidV7 (本地稳定 id, 删除同步可定位).
                        // synced=1: server 还不收 split 副本, drainer 别去推.
                        msg.synced = 1
                        val newId = db.messageDao().insert(msg)
                        msg.id = newId
                        onMessageAppended(msg)
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
        if (chainDepth > ProactiveBudget.HARD_FOLLOWUP_CHAIN_MAX) return
        ProactiveFollowUpWorker.schedule(
            context = context,
            sessionId = sessionId,
            assistantId = assistantId,
            previousIntent = followUp.intent,
            delaySec = followUp.afterSec,
            chainDepth = chainDepth,
        )
    }
}
