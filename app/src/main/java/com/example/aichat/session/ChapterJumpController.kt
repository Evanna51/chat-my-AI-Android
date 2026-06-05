package com.example.aichat.session

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewParent
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aichat.Message
import com.example.aichat.MessageAdapter
import com.example.aichat.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 章节快速跳转：长会话场景下让用户从「章节列表」对话框跳到某条助手消息。
 *
 * 从 ChatSessionActivity 抽出（R8）。把章节跳转相关的 9 个 private fun
 * + 1 个 ChapterJumpItem 类收口到这里。
 *
 * Activity 通过 [Host] 接口提供：
 * - 当前的全部消息列表（用于构建章节项）
 * - 「历史区是否展开」的 get/set（跳转到历史区消息时自动展开）
 *
 * Activity 在构造时把两个 RecyclerView + ScrollView + 两个 Adapter 引用
 * 注入进来；这些 view 在 onCreate 之后稳定不变。
 */
class ChapterJumpController(
    private val activity: Activity,
    private val scrollMessagesView: NestedScrollView,
    private val recyclerHistory: RecyclerView,
    private val recyclerCurrent: RecyclerView,
    private val historyAdapter: MessageAdapter,
    private val currentAdapter: MessageAdapter,
    private val mainHandler: Handler,
    private val host: Host,
) {

    interface Host {
        /** 当前完整消息列表（用于构建章节项 —— 不止 adapter 里能看到的可见消息）*/
        fun currentMessages(): List<Message>
        /** 历史区当前是否展开 */
        val historyExpanded: Boolean
        /** 展开/收起历史区 */
        fun setHistoryExpanded(expanded: Boolean)
    }

    private data class ChapterJumpItem(
        val index: Int,
        val preview: String,
        val messageId: Long,
        val createdAt: Long,
    )

    companion object {
        private const val MAX_JUMP_RETRIES = 12
        private const val JUMP_RETRY_DELAY_MS = 60L
        private const val PREVIEW_MAX_CHARS = 40
        private const val SCROLL_TOP_MARGIN_DP = 8f
    }

    /** Public entry：打开章节跳转对话框 */
    fun show() {
        val items = buildItems()
        if (items.isEmpty()) {
            Toast.makeText(activity, R.string.no_assistant_chapters, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = Array<CharSequence>(items.size) { i ->
            val one = items[i]
            val prefix = "章节${one.index}："
            val text = prefix + one.preview
            SpannableString(text).also { s ->
                s.setSpan(StyleSpan(Typeface.BOLD), 0, prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                s.setSpan(RelativeSizeSpan(0.875f), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.quick_jump_chapters)
            .setItems(labels) { _, which ->
                if (which < 0 || which >= items.size) return@setItems
                val target = items[which]
                jumpTo(target.createdAt, target.messageId)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildItems(): List<ChapterJumpItem> {
        val out = ArrayList<ChapterJumpItem>()
        var chapterIndex = 1
        for (m in host.currentMessages()) {
            if (m.role != Message.ROLE_ASSISTANT) continue
            val content = m.content?.trim() ?: ""
            if (content.isEmpty()) continue
            out.add(
                ChapterJumpItem(
                    index = chapterIndex++,
                    messageId = m.id,
                    createdAt = m.createdAt,
                    preview = buildPreview(content),
                )
            )
        }
        return out
    }

    private fun buildPreview(content: String): String {
        for (line in content.split(Regex("\\r?\\n"))) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            return if (trimmed.length > PREVIEW_MAX_CHARS)
                trimmed.substring(0, PREVIEW_MAX_CHARS) + "…"
            else trimmed
        }
        val fallback = content.trim()
        return if (fallback.length > PREVIEW_MAX_CHARS)
            fallback.substring(0, PREVIEW_MAX_CHARS) + "…"
        else fallback
    }

    private fun jumpTo(createdAt: Long, messageId: Long) {
        if (containsMessage(historyAdapter, createdAt, messageId) && !host.historyExpanded) {
            host.setHistoryExpanded(true)
        }
        attemptJump(createdAt, messageId, 0)
    }

    private fun attemptJump(createdAt: Long, messageId: Long, attempt: Int) {
        var moved = scrollToMessageInRecycler(recyclerHistory, historyAdapter, createdAt, messageId)
        if (!moved) {
            moved = scrollToMessageInRecycler(recyclerCurrent, currentAdapter, createdAt, messageId)
        }
        if (moved) return
        if (attempt >= MAX_JUMP_RETRIES) {
            Toast.makeText(activity, R.string.chapter_jump_failed, Toast.LENGTH_SHORT).show()
            return
        }
        mainHandler.postDelayed(
            { attemptJump(createdAt, messageId, attempt + 1) },
            JUMP_RETRY_DELAY_MS,
        )
    }

    private fun scrollToMessageInRecycler(
        recyclerView: RecyclerView,
        adapter: MessageAdapter,
        createdAt: Long,
        messageId: Long,
    ): Boolean {
        val list = adapter.getMessages()
        var pos = -1
        for (i in list.indices) {
            if (matchesJumpTarget(list[i], createdAt, messageId)) {
                pos = i
                break
            }
        }
        if (pos < 0) return false
        val layoutManager = recyclerView.layoutManager
        if (layoutManager is LinearLayoutManager) {
            layoutManager.scrollToPositionWithOffset(pos, 0)
        } else {
            recyclerView.scrollToPosition(pos)
        }
        val vh = recyclerView.findViewHolderForAdapterPosition(pos)
        var itemView: View? = vh?.itemView
        if (itemView == null) {
            itemView = layoutManager?.findViewByPosition(pos)
        }
        if (itemView == null) return false
        val timestampView: View? = itemView.findViewById(R.id.textTimestamp)
        val targetY = computeScrollYInContainer(timestampView ?: itemView)
        if (targetY < 0) return false
        val margin = (SCROLL_TOP_MARGIN_DP * activity.resources.displayMetrics.density).toInt()
        scrollMessagesView.smoothScrollTo(0, maxOf(0, targetY - margin))
        return true
    }

    private fun containsMessage(adapter: MessageAdapter, createdAt: Long, messageId: Long): Boolean {
        for (one in adapter.getMessages()) {
            if (matchesJumpTarget(one, createdAt, messageId)) return true
        }
        return false
    }

    private fun matchesJumpTarget(one: Message?, createdAt: Long, messageId: Long): Boolean {
        if (one == null) return false
        if (messageId > 0 && one.id > 0) return one.id == messageId
        return createdAt > 0 && one.createdAt == createdAt
    }

    private fun computeScrollYInContainer(targetView: View?): Int {
        if (targetView == null) return -1
        val child = scrollMessagesView.getChildAt(0) ?: return -1
        var y = 0
        var cursor: View? = targetView
        while (cursor != null && cursor != child) {
            y += cursor.top - cursor.scrollY
            val parent: ViewParent = cursor.parent
            if (parent !is View) return -1
            cursor = parent
        }
        return if (cursor == child) y else -1
    }
}
