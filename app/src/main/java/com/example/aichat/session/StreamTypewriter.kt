package com.example.aichat.session

import android.os.Handler
import com.example.aichat.Message
import com.example.aichat.MessageAdapter

/**
 * 流式打字机 —— 把后端按行/按 token 推来的 delta 字符以平稳节奏喂给 UI，
 * 避免一次性整段插入造成卡顿 / 视觉跳跃。
 *
 * 从 ChatSessionActivity 抽出（R9）。内部维护两条独立的 schedule：
 *
 * **打字机帧循环 (typewriterRunnable)**
 * - 每 16ms 一帧，从 pendingStreamChars 取最多 4 个字符
 * - 拼到 streamingTargetMessage.content 后，调 adapter.renderStreamingMessageIfVisible
 *   做 partial 增量重绘（不走 notifyDataSetChanged，避免整个 RV invalidate）
 * - 如果当时没有可见 holder（消息滚出视口/还没 bind），fallback 到 [schedule]
 *
 * **render throttle (renderRunnable)**
 * - 当 pendingStreamChars 数量越界（≥ 80）触发节流：从 24ms → 48ms
 * - 用 [Host.applyMessagesFully] 让 Activity 重新做完整数据装配
 *
 * Activity 通过 [Host] 暴露：
 * - [Host.isFinishingOrDestroyed] —— 异步回调时检查避免触碰已 detach 的 View
 * - [Host.onTickRendered] —— 每次成功 tick 后给 Activity 做自动滚到底机会
 * - [Host.applyMessagesFully] —— 退化到全量装配（找不到 visible holder 时）
 *
 * 注意：`streamingTargetMessage` 是 typewriter 内部 ref，与 Activity 的
 * `activeStreamingMessage` 不是同一个东西 —— 后者代表「当前正在被 LLM 流式
 * 生成的 assistant message」，typewriter 的 target 只在 typewriter 自己的
 * 帧循环内有意义。生命周期通常一致但不应耦合。
 */
class StreamTypewriter(
    private val mainHandler: Handler,
    private val historyAdapter: MessageAdapter,
    private val currentAdapter: MessageAdapter,
    private val host: Host,
) {

    interface Host {
        fun isFinishingOrDestroyed(): Boolean
        /** 成功 tick 后调用 —— Activity 据此判断是否要 auto-scroll-to-bottom */
        fun onTickRendered()
        /** 没有可见 holder / flushNow 时调用 —— Activity 走完整 applyMessagesAndTitle 路径 */
        fun applyMessagesFully()
    }

    companion object {
        private const val RENDER_THROTTLE_MS = 24L
        private const val RENDER_THROTTLE_BUSY_MS = 48L
        private const val RENDER_BUSY_PENDING_CHARS = 80
        private const val FRAME_MS = 16L
        private const val CHARS_PER_FRAME = 4
    }

    private var renderPending = false
    private var lastRenderAt = 0L
    private var streamingTargetMessage: Message? = null
    private val pendingChars = StringBuilder()
    private var typewriterRunning = false

    private val renderRunnable = Runnable {
        renderPending = false
        lastRenderAt = System.currentTimeMillis()
        renderTick(streamingTargetMessage)
    }

    private val typewriterRunnable = object : Runnable {
        override fun run() {
            if (host.isFinishingOrDestroyed()) {
                typewriterRunning = false
                return
            }
            if (streamingTargetMessage == null) {
                typewriterRunning = false
                pendingChars.setLength(0)
                return
            }
            if (pendingChars.isEmpty()) {
                typewriterRunning = false
                return
            }
            val take = minOf(CHARS_PER_FRAME, pendingChars.length)
            val delta = pendingChars.substring(0, take)
            pendingChars.delete(0, take)
            val targetMsg = streamingTargetMessage
            val old = targetMsg?.content ?: ""
            targetMsg?.content = old + delta
            var rendered = historyAdapter.renderStreamingMessageIfVisible(targetMsg)
            rendered = rendered or currentAdapter.renderStreamingMessageIfVisible(targetMsg)
            if (!rendered) {
                schedule()
            } else {
                host.onTickRendered()
            }
            if (pendingChars.isNotEmpty()) {
                mainHandler.postDelayed(this, FRAME_MS)
            } else {
                typewriterRunning = false
            }
        }
    }

    /** Activity 在「LLM 流式开始」时设置 target，使后续 enqueueDelta 知道往哪条消息追加 */
    fun setTarget(message: Message?) {
        if (streamingTargetMessage !== message) {
            streamingTargetMessage = message
        }
    }

    /** 收到一个 delta 段，加入 pending 队列并保证帧循环在跑 */
    fun enqueueDelta(message: Message?, delta: String?) {
        if (message == null || delta.isNullOrEmpty()) return
        if (streamingTargetMessage !== message) {
            streamingTargetMessage = message
            pendingChars.setLength(0)
        }
        pendingChars.append(delta)
        if (typewriterRunning) return
        typewriterRunning = true
        mainHandler.post(typewriterRunnable)
    }

    /** 调度一次 throttled render；多次调用会被合并 */
    fun schedule() {
        val throttle = if (pendingChars.length >= RENDER_BUSY_PENDING_CHARS)
            RENDER_THROTTLE_BUSY_MS else RENDER_THROTTLE_MS
        val now = System.currentTimeMillis()
        val wait = maxOf(0L, throttle - (now - lastRenderAt))
        if (renderPending) return
        renderPending = true
        mainHandler.postDelayed(renderRunnable, wait)
    }

    /**
     * 立即同步 flush：把 throttle 队列清掉，让 Activity 重新装配完整列表。
     * 用于流结束 / cancel 后立刻把 UI 收尾。
     */
    fun flushNow() {
        mainHandler.removeCallbacks(renderRunnable)
        renderPending = false
        lastRenderAt = System.currentTimeMillis()
        host.applyMessagesFully()
    }

    /**
     * 停止帧循环。clearPending=true 时把未消化的字符全丢掉（用户主动 cancel
     * 场景），false 时保留供 [drainPendingTo] 把残余字符一次性塞回消息体。
     */
    fun stop(clearPending: Boolean) {
        mainHandler.removeCallbacks(typewriterRunnable)
        typewriterRunning = false
        if (clearPending) pendingChars.setLength(0)
    }

    /**
     * 流正常结束时调用：把 pending 里残留的字符一次性 append 到消息内容，
     * 然后停止帧循环。避免帧循环还没消费完就 LLM 已经发完 onSuccess
     * 导致末尾几个字符丢失。
     */
    fun drainPendingTo(message: Message?) {
        if (message == null) {
            stop(true)
            return
        }
        mainHandler.removeCallbacks(typewriterRunnable)
        typewriterRunning = false
        if (pendingChars.isNotEmpty()) {
            val old = message.content ?: ""
            message.content = old + pendingChars.toString()
            pendingChars.setLength(0)
        }
    }

    /** 流真正结束 / Activity 销毁时彻底重置 */
    fun resetTarget() {
        streamingTargetMessage = null
        mainHandler.removeCallbacks(renderRunnable)
        renderPending = false
    }

    private fun renderTick(message: Message?) {
        if (host.isFinishingOrDestroyed()) return
        var updated = false
        if (message != null) {
            updated = updated or historyAdapter.notifyMessageChanged(message)
            updated = updated or currentAdapter.notifyMessageChanged(message)
        }
        if (!updated) {
            host.applyMessagesFully()
            return
        }
        host.onTickRendered()
    }
}
