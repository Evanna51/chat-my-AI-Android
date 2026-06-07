package com.example.aichat.adapter

import android.view.View

/**
 * 控制助手气泡上「展开/折叠」浮动胶囊（toggle pill）的 translationY，
 * 让它跟随 RecyclerView viewport：
 *
 * - 气泡完全在 viewport 内 → translationY = 0，胶囊呆在气泡右下角
 * - 气泡底部超出 viewport 底部 → 把胶囊向上抬，幅度刚好停留在 viewport 内
 * - 抬升上限：胶囊不能跑到气泡顶部之外
 * - 气泡完全不在 viewport 内 → no-op（保留上次 translationY，省 invalidate）
 *
 * viewport 未初始化（top/bottom 仍为 [Int.MIN_VALUE]）时 [applyAffix] 是 no-op，
 * 等 Activity 给出 viewport 边界后才生效。
 *
 * 这里只持有 viewport 状态 + 单个 holder 的几何计算；attached holders 集合由
 * MessageAdapter 自己维护（还会被流式渲染 / voice play 等非 affix 路径共用）。
 */
class CollapseAffixController {
    private var viewportTop: Int = Int.MIN_VALUE
    private var viewportBottom: Int = Int.MIN_VALUE

    /** viewport 是否已通过 [setViewport] 初始化。*/
    val hasViewport: Boolean
        get() = viewportTop != Int.MIN_VALUE && viewportBottom != Int.MIN_VALUE

    fun setViewport(top: Int, bottom: Int) {
        viewportTop = top
        viewportBottom = bottom
    }

    /**
     * @param toggle 折叠/展开胶囊 View
     * @param bubble 气泡容器 View（决定气泡几何范围）
     * @param density 屏幕密度（dp → px 换算，6dp 边距用）
     */
    fun applyAffix(toggle: View, bubble: View, density: Float) {
        if (toggle.visibility != View.VISIBLE) return
        if (!hasViewport) return
        if (bubble.height <= 0 || toggle.height <= 0) return
        val pos = IntArray(2)
        bubble.getLocationOnScreen(pos)
        val bubbleTopAbs = pos[1]
        val bubbleBottomAbs = bubbleTopAbs + bubble.height
        if (bubbleBottomAbs <= viewportTop || bubbleTopAbs >= viewportBottom) return

        val gapPx = 6f * density
        val newY: Float = if (bubbleBottomAbs <= viewportBottom) {
            0f
        } else {
            val pullUp = (bubbleBottomAbs - viewportBottom).toFloat()
            val maxPullUp = (bubble.height - toggle.height - gapPx * 2f).coerceAtLeast(0f)
            -minOf(pullUp, maxPullUp)
        }
        if (toggle.translationY != newY) toggle.translationY = newY
    }
}
