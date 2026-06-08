package com.example.aichat.story

/**
 * 从章节标题或消息片段中提取章节序号（Int）。
 *
 * 支持格式（中文 / 阿拉伯数字混用）：
 *   第一章 / 第1章 / 第十一章 / 第一回 / 第四节 / 章节一 / 章节11
 *   第二十三回 / 第一百章 / 第两百五十章
 */
object ChapterNumberParser {

    private val HEADING_RE = Regex(
        """第\s*([零〇两一二三四五六七八九十百千\d]+)\s*[章回节卷部]""" + "|" +
            """[章节]\s*([零〇两一二三四五六七八九十百千\d]+)"""
    )

    /** 从文本中提取章节序号，无法识别时返回 null。 */
    fun extract(text: String): Int? {
        val m = HEADING_RE.find(text) ?: return null
        val raw = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
        return raw.toIntOrNull() ?: chineseToInt(raw)
    }

    /**
     * 将中文数字字符串转为 Int，仅支持常见章节范围（1–9999）。
     * 纯阿拉伯的情况已在调用方通过 toIntOrNull() 处理。
     */
    fun chineseToInt(s: String): Int? {
        if (s.isBlank()) return null
        val digitMap = mapOf(
            '零' to 0, '〇' to 0, '两' to 2,
            '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )
        var total = 0
        var section = 0
        for (c in s) {
            when (c) {
                in digitMap -> section = section * 10 + (digitMap[c] ?: return null)
                '十' -> { if (section == 0) section = 1; total += section * 10; section = 0 }
                '百' -> { if (section == 0) return null; total += section * 100; section = 0 }
                '千' -> { if (section == 0) return null; total += section * 1000; section = 0 }
                else -> return null
            }
        }
        total += section
        return if (total > 0) total else null
    }
}
