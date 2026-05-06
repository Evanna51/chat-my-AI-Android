package com.example.aichat.chat

import java.util.Calendar

/**
 * 角色对话开头的"当前时间"上下文工具.
 *
 * 角色扮演场景里时间是强相关的: 早上/深夜的语气不同, 周一周末的状态不同,
 * 节气/节日会被角色自然提起. 这个 utility 在 ChatViewModel 的 system prompt
 * 注入路径上 prepend 一行简短描述, 让模型知道"现在是什么时候".
 *
 * 设计原则:
 * - 极短 (≤80 字符), 不挤占 system prompt
 * - 包含: 日期 + 周几 + 时段 (早晨/上午/...) + 节气/节日 (如果有)
 * - 节气与节日采用近似日期 (±1 天容忍), 不引入 lunar 计算库
 *
 * 例子输出:
 *   `[现在] 2026-05-06 周三 14:30 下午`
 *   `[现在] 2026-05-05 周二 09:00 上午 · 立夏`
 *   `[现在] 2026-10-01 周四 18:00 傍晚 · 国庆节`
 */
object ChatTimeContext {

    /**
     * 返回当前时间描述. 用于直接 prepend 到 systemPrompt.
     * @return 单行字符串, 末尾不带换行
     */
    @JvmStatic
    fun describeNow(): String = describeAt(Calendar.getInstance())

    /** 测试 / 重放友好: 接受外部 Calendar 实例. */
    @JvmStatic
    fun describeAt(cal: Calendar): String {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val min = cal.get(Calendar.MINUTE)
        val datePart = "%04d-%02d-%02d".format(y, m, d)
        val timePart = "%02d:%02d".format(h, min)
        val dow = chineseDayOfWeek(cal)
        val seg = timeSegment(h)

        val occasion = festivalOrSolarTerm(m, d)
        val base = "$datePart $dow $timePart $seg"
        return if (occasion.isNotEmpty()) "[现在] $base · $occasion" else "[现在] $base"
    }

    private fun chineseDayOfWeek(cal: Calendar): String {
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "周一"
            Calendar.TUESDAY -> "周二"
            Calendar.WEDNESDAY -> "周三"
            Calendar.THURSDAY -> "周四"
            Calendar.FRIDAY -> "周五"
            Calendar.SATURDAY -> "周六"
            Calendar.SUNDAY -> "周日"
            else -> ""
        }
    }

    private fun timeSegment(hour: Int): String {
        return when (hour) {
            in 0..4 -> "凌晨"
            in 5..7 -> "清晨"
            in 8..11 -> "上午"
            in 12..13 -> "中午"
            in 14..17 -> "下午"
            in 18..19 -> "傍晚"
            in 20..22 -> "晚上"
            23 -> "深夜"
            else -> ""
        }
    }

    /**
     * 节气 / 节日命中. ±1 天容忍 (节气逐年误差 ≤1 天, 不查 lunar 表).
     * 返回单个最显著的标签, 多个匹配优先节日 > 节气.
     * 月份是 1..12 (Calendar.MONTH + 1).
     */
    private fun festivalOrSolarTerm(month: Int, day: Int): String {
        // 公历节日 (固定日期, 高优先级)
        FIXED_FESTIVALS[month to day]?.let { return it }
        // 24 节气近似 (±1 天匹配)
        for ((mmdd, name) in SOLAR_TERMS) {
            val (mm, dd) = mmdd
            if (mm == month && kotlin.math.abs(dd - day) <= 1) return name
        }
        return ""
    }

    /** 公历固定节日. 农历相关 (春节 / 中秋 / 端午 / 七夕 / 元宵) 暂不收录, 留待 lunar 库接入. */
    private val FIXED_FESTIVALS: Map<Pair<Int, Int>, String> = mapOf(
        (1 to 1) to "元旦",
        (2 to 14) to "情人节",
        (3 to 8) to "妇女节",
        (3 to 12) to "植树节",
        (4 to 5) to "清明节",  // 大致, 实际跟节气走 4-4..4-6
        (5 to 1) to "劳动节",
        (5 to 4) to "青年节",
        (6 to 1) to "儿童节",
        (9 to 10) to "教师节",
        (10 to 1) to "国庆节",
        (10 to 24) to "程序员节",
        (12 to 24) to "平安夜",
        (12 to 25) to "圣诞节",
    )

    /**
     * 24 节气近似日期 (公历). 实际节气日子在每年的当日 ±1 天浮动, 已在
     * [festivalOrSolarTerm] 中按 ±1 容忍匹配.
     */
    private val SOLAR_TERMS: List<Pair<Pair<Int, Int>, String>> = listOf(
        (2 to 4) to "立春",
        (2 to 19) to "雨水",
        (3 to 6) to "惊蛰",
        (3 to 21) to "春分",
        (4 to 5) to "清明",
        (4 to 20) to "谷雨",
        (5 to 6) to "立夏",
        (5 to 21) to "小满",
        (6 to 6) to "芒种",
        (6 to 21) to "夏至",
        (7 to 7) to "小暑",
        (7 to 23) to "大暑",
        (8 to 8) to "立秋",
        (8 to 23) to "处暑",
        (9 to 8) to "白露",
        (9 to 23) to "秋分",
        (10 to 8) to "寒露",
        (10 to 24) to "霜降",
        (11 to 7) to "立冬",
        (11 to 22) to "小雪",
        (12 to 7) to "大雪",
        (12 to 22) to "冬至",
        (1 to 6) to "小寒",
        (1 to 20) to "大寒",
    )
}
