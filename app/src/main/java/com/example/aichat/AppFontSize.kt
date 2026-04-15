package com.example.aichat

import android.content.Context

/**
 * 应用字号工具 — 以用户配置的基础字号为基准，用加减法定义各级字号。
 *
 * 用法：textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, AppFontSize.sp(ctx, -2))
 *
 * 各级 offset（整数，无小数）：
 *   正文   body:       0   示例 base=14 → 14sp
 *   小字   caption:   -2   示例 base=14 → 12sp
 *   思考流  reasoning: -1   示例 base=14 → 13sp
 *   极小   small:     -4   示例 base=14 → 10sp
 *   稍大   large:     +2   示例 base=14 → 16sp
 *   标题   title:     +4   示例 base=14 → 18sp
 *
 * 注：context 必须是 ThemedActivity 的 context（已包含 fontScale override），
 * sp() 内部会自动补偿 fontScale，使最终渲染尺寸恰好等于 base + offset。
 */
object AppFontSize {

    /** 正文：与用户配置字号完全一致 */
    fun body(context: Context): Float = sp(context, 0)

    /** 时间戳 / 次要信息：比正文小 2 */
    fun caption(context: Context): Float = sp(context, -2)

    /** 思考流内容：比正文小 1 */
    fun reasoning(context: Context): Float = sp(context, -1)

    /** 极小字：比正文小 4（元数据、角标等） */
    fun small(context: Context): Float = sp(context, -4)

    /** 稍大：比正文大 2 */
    fun large(context: Context): Float = sp(context, +2)

    /** 标题：比正文大 4 */
    fun title(context: Context): Float = sp(context, +4)

    /**
     * 通用方法：传入整数 offset，返回 SP 值。
     *
     * 补偿公式：fontScale = base / 14，
     * 使 textView.setTextSize(SP, result) 最终渲染为 (base + offset) dp。
     *   result = (base + offset) * 14 / base
     */
    fun sp(context: Context, offset: Int): Float {
        val base = ConfigManager(context).getFontSize()
        val target = (base + offset).coerceAtLeast(8)
        return (target * 14f / base).coerceAtLeast(6f)
    }
}
