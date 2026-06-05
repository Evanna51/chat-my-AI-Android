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
    /** Called when a message row was rewritten (split[0] in-place rewrite, or
     *  split[i] placeholder → final content). */
    private val onMessageReplaced: (rowId: Long, newContent: String) -> Unit,
    /** Called when a fresh row was inserted (split[1..N] placeholder, or follow-up). */
    private val onMessageAppended: (msg: Message) -> Unit,
    /** Called when a row should be removed (cancelled split placeholder). */
    private val onMessageRemoved: (rowId: Long) -> Unit = {},
) {

    companion object {
        private const val TAG = "ProactiveChatPlanner"

        /** Split parts 间的最小间隔 (固定 "对方读 + 重新打字" 缓冲). */
        private const val SPLIT_MIN_INTERVAL_MS = 2500L

        /** 单段最长间隔, 防止 AI 写一大段然后等很久. */
        private const val SPLIT_MAX_INTERVAL_MS = 8000L

        /** 每个字符模拟打字时长. 中文 ~80ms 接近真人. */
        private const val SPLIT_PER_CHAR_MS = 80L

        /** Split 间隔时插入的 typing placeholder 文本; 与 MessageAdapter/ChatSessionActivity
         *  里的常量保持一致, MessageAdapter 据此识别并显示 typing 动画. */
        private const val LOADING_PLACEHOLDER_TEXT = "[...正在输入中]"
    }

    /**
     * 单段间隔: 基于前一段长度模拟"对方在读 + 重新打字"时长.
     * 链式调度时, 这就是当前 placeholder 显示 typing 动画的时长.
     */
    private fun computeSplitGapMs(prevPartLen: Int): Long {
        return (SPLIT_MIN_INTERVAL_MS + SPLIT_PER_CHAR_MS * prevPartLen)
            .coerceAtMost(SPLIT_MAX_INTERVAL_MS)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Per-session split scheduling state — for cleanup on cancel. */
    private val pendingSplitRunnables = ConcurrentHashMap<String, MutableList<Runnable>>()
    /** Per-session 已插入但 content 还是 LOADING_PLACEHOLDER_TEXT 的 placeholder row id.
     *  cancel 时这些 row 要从 DB + in-memory 一并清掉. */
    private val pendingSplitPlaceholderIds = ConcurrentHashMap<String, MutableList<Long>>()

    /**
     * Called by ChatViewModel after it inserts a consolidated Message for the assistant turn.
     *
     * @param splitGroupTurnId split[0] 行的 turnId. split[1..N] 将共用此 turnId
     *   (保持 synced=1, 不推服务端), 以便删除时通过 turnId 一次性清掉整组.
     */
    fun onAssistantTurnFinalized(
        sessionId: String,
        assistantId: String,
        insertedMessageId: Long,
        splitGroupTurnId: String,
        @Suppress("UNUSED_PARAMETER") cleanContent: String,
        meta: ProactiveMeta?,
        options: SessionChatOptions,
    ) {
        Log.d(TAG, "onAssistantTurnFinalized: sid=$sessionId aid=$assistantId autoChatEnabled=${options.autoChatEnabled} meta=${meta?.let { "split=${it.split?.size} followUp=${it.followUp?.afterSec}s autoStop=${it.autoStop}" } ?: "null"}")
        if (sessionId.isEmpty()) return
        if (!options.autoChatEnabled) {
            Log.w(TAG, "autoChatEnabled=false, skip (shouldn't reach here)")
            return
        }

        cancelFollowUp(sessionId)
        cancelPendingSplits(sessionId)

        // meta == null: 模型没有遵守协议（无任何 marker）.
        // split 需要模型输出分段 marker，无法兜底；但 follow-up 可以用 fallback.
        if (meta == null) {
            val fallback = buildFallbackFollowUp(assistantId)
            Log.d(TAG, "meta=null → fallback=${fallback?.let { "afterSec=${it.afterSec}" } ?: "null (allowProactiveMessage=false?)"}")
            if (fallback != null) {
                scheduleFollowUp(sessionId, assistantId, fallback, 1)
            }
            return
        }

        applySplit(sessionId, assistantId, insertedMessageId, splitGroupTurnId, meta.split)
        // autoStop 是硬刹车: 即便 followUp 非 null, 模型已声明本次不再追问.
        if (meta.autoStop) {
            Log.i(TAG, "autoStop=true; no follow-up scheduled")
            return
        }
        // 模型给了 followUp 就用; 没给的话, 如果角色允许主动消息, 注入默认兜底 followUp.
        val effectiveFollowUp = meta.followUp ?: buildFallbackFollowUp(assistantId)
        Log.d(TAG, "scheduling follow-up: followUp=${effectiveFollowUp?.let { "afterSec=${it.afterSec} intent=${it.intent}" } ?: "null (no schedule)"}")
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
        val list = pendingSplitRunnables.remove(sessionId)
        if (list != null) for (r in list) mainHandler.removeCallbacks(r)
        // 清掉已插入的 typing placeholder (DB + 通知 UI 移除 in-memory).
        val ids = pendingSplitPlaceholderIds.remove(sessionId)
        if (!ids.isNullOrEmpty()) {
            val snapshot = ids.toList()
            com.example.aichat.IngestExecutor.execute {
                for (id in snapshot) {
                    try {
                        db.messageDao().deleteById(id)
                    } catch (_: Exception) {}
                    try {
                        onMessageRemoved(id)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Called from ChatViewModel.onCleared (user left chat page). We do NOT
     * cancel pending split runnables here — they run on mainHandler (the
     * application looper) and write DB via [com.example.aichat.IngestExecutor],
     * both of which outlive ViewModel. Letting them complete means the user
     * sees all split segments persisted when they reopen the chat.
     *
     * For active user-initiated cancellation (Stop button), use
     * [cancelPendingSplits] instead — that one does cancel the runnables.
     */
    fun shutdown() {
        // Drop our session-keyed bookkeeping; runnables themselves keep firing.
        pendingSplitRunnables.clear()
        pendingSplitPlaceholderIds.clear()
    }

    // ─────────────────────────── Split handling ───────────────────────────

    private fun applySplit(
        sessionId: String,
        assistantId: String,
        insertedMessageId: Long,
        splitGroupTurnId: String,
        split: List<String>?,
    ) {
        if (split == null || split.size < 2) return
        if (insertedMessageId <= 0) return

        // App-scoped executor: split DB writes MUST complete even after user
        // leaves chat page (ViewModel cleared, its executor shut down).
        // See [com.example.aichat.IngestExecutor].
        com.example.aichat.IngestExecutor.execute {
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
        val placeholderIds = mutableListOf<Long>()
        pendingSplitPlaceholderIds[sessionId] = placeholderIds

        // 链式调度: 立即为 split[1] 插 typing placeholder, 显示间隔结束后改写为正文,
        // 同时为 split[2] 插下一个 placeholder ... 保证同一时刻最多 1 个 typing bubble.
        scheduleNextSplitPart(
            sessionId, assistantId, splitGroupTurnId, split, 1,
            list, placeholderIds,
        )
    }

    /**
     * 链式调度 split[idx]: 立刻 insert 一个 typing placeholder 行, 等
     * computeSplitGapMs(prev) 时长后把它的 content 改成 split[idx], 然后递归调度
     * split[idx+1]. 任一步失败/取消都不会破坏整体: cancel 会从 pendingSplitPlaceholderIds
     * 清掉未填充的 placeholder.
     */
    private fun scheduleNextSplitPart(
        sessionId: String,
        assistantId: String,
        splitGroupTurnId: String,
        split: List<String>,
        idx: Int,
        list: MutableList<Runnable>,
        placeholderIds: MutableList<Long>,
    ) {
        if (idx >= split.size) return
        val prevLen = split.getOrNull(idx - 1)?.length ?: 0
        val gapMs = computeSplitGapMs(prevLen)

        com.example.aichat.IngestExecutor.execute {
            try {
                val placeholder = Message(sessionId, Message.ROLE_ASSISTANT, LOADING_PLACEHOLDER_TEXT)
                placeholder.assistantId = assistantId
                placeholder.proactiveKind = 1
                // 共用 split[0] 的 turnId: 删除时可通过 turnId 一次性清掉整组孤儿行.
                // synced=1: server 不收 split 副本, drainer 不推.
                placeholder.turnId = splitGroupTurnId
                placeholder.synced = 1
                val pid = db.messageDao().insert(placeholder)
                placeholder.id = pid
                synchronized(placeholderIds) { placeholderIds.add(pid) }
                onMessageAppended(placeholder)

                val r = Runnable {
                    com.example.aichat.IngestExecutor.execute {
                        try {
                            db.messageDao().updateContentAndProactiveKind(pid, split[idx], 1)
                            synchronized(placeholderIds) { placeholderIds.remove(pid) }
                            onMessageReplaced(pid, split[idx])
                            scheduleNextSplitPart(
                                sessionId, assistantId, splitGroupTurnId, split,
                                idx + 1, list, placeholderIds,
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "split[$idx] content fill failed", e)
                        }
                    }
                }
                list.add(r)
                mainHandler.postDelayed(r, gapMs)
            } catch (e: Exception) {
                Log.w(TAG, "split[$idx] placeholder insert failed", e)
            }
        }
    }

    // ─────────────────────────── Follow-up handling ───────────────────────────

    private fun scheduleFollowUp(
        sessionId: String,
        assistantId: String,
        followUp: ProactiveFollowUp?,
        chainDepth: Int,
    ) {
        if (followUp == null) {
            Log.d(TAG, "scheduleFollowUp: followUp=null, nothing enqueued")
            return
        }
        if (chainDepth > ProactiveBudget.HARD_FOLLOWUP_CHAIN_MAX) {
            Log.i(TAG, "scheduleFollowUp: chainDepth=$chainDepth > hard max, skip")
            return
        }
        Log.i(TAG, "scheduleFollowUp: enqueuing WorkManager job afterSec=${followUp.afterSec} chainDepth=$chainDepth intent='${followUp.intent}'")
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
