package com.example.aichat.session

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Toast
import androidx.core.widget.NestedScrollView
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

    /**
     * 跳转入口。Bug 修复（2026-06-06）：
     *
     * **旧行为的 3 个失败模式**：
     *
     * 1. **同步 expand → 同步 attemptJump**：`setHistoryExpanded(true)` 只改 visibility，
     *    layout pass 还没跑，recyclerHistory 的子 view 的 `top` 都是 0/stale —— 立刻
     *    `findViewByPosition` 拿到的 view 即使存在，坐标也是错的。
     *
     * 2. **`scrollToPositionWithOffset` 主动有害**：history / current 两个 RV 都跑在
     *    `wrap_content` + `nestedScrollingEnabled=false` 的 NestedScrollView 里 ——
     *    自身没有可滚动区，LinearLayoutManager 会把所有 item 一字排开。这时调
     *    `scrollToPositionWithOffset(pos, 0)` 会触发一次诡异的内部 layout，意图把 pos
     *    放到 RV 顶部，但实际上把 item 偏移搞乱，导致 `view.top` 不再代表 item 在
     *    NSV 坐标空间里的真实 Y。NSV 才是真正的 scroller。
     *
     * 3. **`computeScrollYInContainer` 用 view.top 累加**：依赖最新一次 layout 已完成。
     *    若有人正在收/展某条消息 (`notifyItemChanged` → requestLayout)，这条 path 上
     *    的 `top` 全是 stale 值。
     *
     * **新行为**：
     * - expand 后挂 [OneShotPreDrawListener](androidx.core.view.OneShotPreDrawListener) 等下一次 layout。
     * - 用 [View.getLocationInWindow] 算坐标，绕开 view.top 累加。
     * - 不再调 `scrollToPositionWithOffset`；item 没 bind 时退而求其次 `scrollToPosition`
     *   并等下一次 pre-draw 重试。
     */
    private fun jumpTo(createdAt: Long, messageId: Long) {
        val needsExpand = !host.historyExpanded && containsMessage(historyAdapter, createdAt, messageId)
        if (needsExpand) {
            host.setHistoryExpanded(true)
        }
        // 等下一次 pre-draw：保证 visibility=VISIBLE 引发的 measure/layout 已完成。
        // 即使没扩展，也等一帧 —— 兜底任何 pending requestLayout（比如刚 toggle
        // 过某条消息的折叠状态），从而避免读到 stale 的 view.top。
        androidx.core.view.OneShotPreDrawListener.add(scrollMessagesView, Runnable {
            attemptJump(createdAt, messageId, 0)
        })
    }

    private fun attemptJump(createdAt: Long, messageId: Long, attempt: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
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
        val layoutManager = recyclerView.layoutManager ?: return false

        // wrap_content + nestedScrollingEnabled=false 的 RV 通常会把所有 item 都
        // 摆出来。但 RV 仍可能在以下场景没 bind 目标 item：
        //   - 刚 setMessages 后第一次 layout 还没完成
        //   - RV 自己被父 layout 给的高度暂时是 0（visibility 刚切 VISIBLE）
        // 这时通知它 scrollToPosition 触发 bind，然后 false 退出让上层 60ms 后再试。
        val itemView: View? = layoutManager.findViewByPosition(pos)
        if (itemView == null) {
            recyclerView.scrollToPosition(pos)
            return false
        }
        if (itemView.width == 0 || itemView.height == 0) {
            // bind 了但还没完成 measure，等一帧。
            return false
        }
        val timestampView: View? = itemView.findViewById(R.id.textTimestamp)
        val anchor = timestampView ?: itemView
        val targetY = computeScrollYInContainer(anchor)
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

    /**
     * 计算 anchor 在 NestedScrollView 内容坐标空间中的 Y。
     *
     * 用 [View.getLocationInWindow] 而不是手动累加 `view.top`：
     *   - `getLocationInWindow` 反映**当前帧实际绘制位置**，已考虑父 scrollY、
     *     变换矩阵等；不需要全部祖先都 layout 干净
     *   - 子视图 `top` 是相对父容器**在最近一次 layout 完成后**的位置 ——
     *     若有 pending requestLayout，walk 一路就会读到不一致的值
     *
     * NSV 的 child（外层 LinearLayout）的屏幕 Y =（NSV 屏幕 Y - NSV.scrollY）。
     * anchor 在 child 内容空间的 Y = anchor 屏幕 Y - child 屏幕 Y。这两者相减
     * 自动消掉 scrollY，不需要再补加。
     */
    private fun computeScrollYInContainer(targetView: View?): Int {
        if (targetView == null) return -1
        if (!targetView.isAttachedToWindow) return -1
        val child = scrollMessagesView.getChildAt(0) ?: return -1
        if (!child.isAttachedToWindow) return -1
        val anchorLoc = IntArray(2)
        val childLoc = IntArray(2)
        targetView.getLocationInWindow(anchorLoc)
        child.getLocationInWindow(childLoc)
        val y = anchorLoc[1] - childLoc[1]
        return if (y >= 0) y else -1
    }
}
