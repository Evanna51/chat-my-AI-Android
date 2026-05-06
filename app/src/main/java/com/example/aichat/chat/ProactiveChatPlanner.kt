package com.example.aichat.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.aichat.AppDatabase
import com.example.aichat.Message
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

        /** 同一段回复内 split 各部分的渲染间隔. */
        private const val SPLIT_INTERVAL_MS = 1500L

        /** Follow-up chain 最大长度 (含触发那一条). 与 Worker 内部限制对齐. */
        private const val MAX_FOLLOWUP_CHAIN = 2
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
            val delay = i * SPLIT_INTERVAL_MS
            val r = Runnable {
                executor.execute {
                    try {
                        if (!ProactiveBudget.consumeIfAllowed(context, sessionId)) {
                            Log.i(TAG, "split[$i] suppressed: daily budget exhausted")
                            return@execute
                        }
                        val msg = Message(sessionId, Message.ROLE_ASSISTANT, part)
                        msg.assistantId = assistantId
                        msg.proactiveKind = 1
                        // 不 stamp turnId: server 还不收 split 副本.
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
        if (chainDepth > MAX_FOLLOWUP_CHAIN) return
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
