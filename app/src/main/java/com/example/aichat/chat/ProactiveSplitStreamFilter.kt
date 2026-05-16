package com.example.aichat.chat

/**
 * V9 stream filter: 流式阶段**只显示第一段** + 元信息标记不显示.
 *
 * 核心逻辑: 一旦在 stream 里看到下面任意一种边界, 就进入 swallow 模式 — 后续 chunk
 * 全部吞掉不再 emit:
 *   - `|||` 段间分隔 (V9 协议, 替代 V8 的 `\n\n`, 消除自然段落歧义)
 *   - `||==` 元信息前缀 (FOLLOWUP / STOP / SKIP)
 *
 * 为啥这么设计:
 *   流式阶段只显示第一段, 用户看到的就是 messages[0] —— 跟 onSuccess 后 planner
 *   重写到第一条气泡的内容完全一致, 视觉上 0 跳变. 后续段 [messages[1..N]] 由
 *   ProactiveChatPlanner.applySplit 在 onSuccess 后通过 typewriter 逐条追加, 体验
 *   跟"真人发短信" 一致 — 第一段流完 → 停顿 → 第二段打字冒出来 → 第三段打字冒出来.
 *
 * fullContent 在外面累加是含完整 `|||Nms|||` + 元信息标记的 raw, 由
 * [ProactiveMetaParser] 在 onSuccess 时按 `|||` 切成 messages 数组 + 提元信息.
 *
 * 边界处理:
 *   - chunk 可能在 `|||` / `||==` 中间断, 缓冲最后 3 字符 (max prefix - 1)
 *   - 单段输出 (LLM 不输出 `|||` 也无元信息) 不会触发 swallow, 完整 emit
 */
class ProactiveSplitStreamFilter {

    companion object {
        private const val META_PREFIX = "||=="          // len 4
        private const val SPLIT_MARKER = "|||"          // len 3
        private const val SPLIT_NL = "\n\n\n"           // len 3 — 两个空行 = 消息分段
        // max(4, 3, 3) - 1 = 3
        private const val MAX_HOLD = 3
    }

    private var inSwallow: Boolean = false
    private var heldTail: String = ""

    fun process(chunk: String?): String {
        if (inSwallow) return ""
        val s = chunk ?: ""
        if (s.isEmpty() && heldTail.isEmpty()) return ""
        val combined = heldTail + s

        val firstBoundary = listOf(
            combined.indexOf(META_PREFIX),
            combined.indexOf(SPLIT_MARKER),
            combined.indexOf(SPLIT_NL),
        ).filter { it >= 0 }.minOrNull() ?: -1

        if (firstBoundary >= 0) {
            inSwallow = true
            heldTail = ""
            return combined.substring(0, firstBoundary)
        }
        if (combined.length <= MAX_HOLD) {
            heldTail = combined
            return ""
        }
        val cut = combined.length - MAX_HOLD
        heldTail = combined.substring(cut)
        return combined.substring(0, cut)
    }

    /** 流结束: 没遇到任何边界就把 heldTail 输出 (普通文本). */
    fun flushTail(): String {
        if (inSwallow) {
            heldTail = ""
            return ""
        }
        val out = heldTail
        heldTail = ""
        return out
    }
}
