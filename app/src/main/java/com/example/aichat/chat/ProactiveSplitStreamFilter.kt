package com.example.aichat.chat

/**
 * V10 stream filter: 流式阶段**只显示第一段** + 元信息标记不显示.
 *
 * 一旦在 stream 里看到下面任意一种边界, 就进入 swallow 模式 — 后续 chunk
 * 全部吞掉不再 emit:
 *   - `|||` 段间分隔（显式协议, prompt 教模型用）
 *   - `||==` 元信息前缀（FOLLOWUP / STOP / SKIP）
 *   - 空行 + 行首开括号（隐式自然分段, V10 新增）
 *     括号集: ( [ （ 【  (英文圆/方 + 中文圆/方); 适配模型用括号
 *     开启新动作/心境段的输出习惯
 *
 * 流式 hold 策略 (computeSafeEmit):
 *   - 末尾保留 ≥3 字符防 `|||` / `||==` 跨 chunk 切断
 *   - 末尾连续空白(含 \n)序列暂留, 等待下一非空白字符判定是否为开括号；
 *     MAX_HOLD_BLANK 兜底防 buffer 无界增长
 *
 * fullContent 在外面累加完整 raw, [ProactiveMetaParser] 在 onSuccess 时
 * 按相同规则切成 messages 数组 + 提元信息.
 */
class ProactiveSplitStreamFilter {

    companion object {
        private const val META_PREFIX = "||=="
        private const val SPLIT_MARKER = "|||"
        // 空行 + 行首开括号 — 与 ProactiveMetaParser.SPLIT_NL 对齐
        private val BLANK_BRACKET = Regex("""\n[^\S\n]*\n[^\S\n]*[(\[（【]""")
        private const val MAX_HOLD_MARKER = 3
        private const val MAX_HOLD_BLANK = 64
    }

    private var inSwallow: Boolean = false
    private var heldTail: String = ""

    fun process(chunk: String?): String {
        if (inSwallow) return ""
        val s = chunk ?: ""
        if (s.isEmpty() && heldTail.isEmpty()) return ""
        val combined = heldTail + s

        val explicitIdx = listOf(
            combined.indexOf(META_PREFIX),
            combined.indexOf(SPLIT_MARKER),
        ).filter { it >= 0 }.minOrNull() ?: -1
        val implicitIdx = BLANK_BRACKET.find(combined)?.range?.first ?: -1

        val boundary = listOf(explicitIdx, implicitIdx).filter { it >= 0 }.minOrNull() ?: -1
        if (boundary >= 0) {
            inSwallow = true
            heldTail = ""
            return combined.substring(0, boundary)
        }

        val safeEmit = computeSafeEmit(combined)
        heldTail = combined.substring(safeEmit)
        return combined.substring(0, safeEmit)
    }

    /** 流结束: 没遇到任何边界就把 heldTail 输出. */
    fun flushTail(): String {
        if (inSwallow) {
            heldTail = ""
            return ""
        }
        val out = heldTail
        heldTail = ""
        return out
    }

    /**
     * 返回 combined 中"可安全 emit"的截止位置（右开区间）.
     * hold 末尾两类不确定区:
     *   1. 末 3 字符: 防止 `|||` / `||==` 被跨 chunk 切断
     *   2. 末尾连续空白序列(必须含 \n): 防止"空行 + 开括号"边界被错过
     *      —— 等下次 chunk 来非空白字符再判定; MAX_HOLD_BLANK 兜底
     */
    private fun computeSafeEmit(s: String): Int {
        var hold = MAX_HOLD_MARKER.coerceAtMost(s.length)

        var i = s.length
        while (i > 0) {
            val c = s[i - 1]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i-- else break
        }
        if (i < s.length && s.substring(i).contains('\n')) {
            val blankHold = s.length - i
            if (blankHold <= MAX_HOLD_BLANK) {
                hold = maxOf(hold, blankHold)
            }
        }

        return (s.length - hold).coerceAtLeast(0)
    }
}
