package com.example.aichat.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.example.aichat.R
import com.google.android.material.textfield.TextInputLayout

/**
 * Liquid-glass styled text input.
 *
 * Visual contract:
 * - Inherits everything from [TextInputLayout]: hint behaviour, error state,
 *   selection / copy-paste, password toggle, password visibility, etc.
 * - Adds a 1px highlight along the top half of the rounded rect (think Apple's
 *   inner-glow rim) plus a 1px soft shadow along the bottom half. Both colours
 *   live in [R.color] and have day/night variants.
 * - Box stroke and hint colour are wired to ColorStateList selectors so they
 *   pick up the active theme's `colorPrimary` while focused, and degrade to
 *   neutral grey when idle.
 *
 * Use via the `Widget.AIChat.GlassInput` style.
 */
open class GlassTextInputLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.textInputStyle,
) : TextInputLayout(context, attrs, defStyleAttr) {

    protected val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = ContextCompat.getColor(context, R.color.glass_input_highlight_top)
        strokeCap = Paint.Cap.ROUND
    }
    protected val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = ContextCompat.getColor(context, R.color.glass_input_shadow_bottom)
        strokeCap = Paint.Cap.ROUND
    }
    protected val topPath = Path()
    protected val bottomPath = Path()
    protected val frame = RectF()
    protected val cornerRadius: Float = dp(14f)
    protected val edgeInset: Float = dp(0.5f)

    /**
     * 子类可覆盖：让顶部高光从盒子顶边向下偏移多少 dp。
     * 默认 0 = 沿着上 stroke 走；浮动标签变体会传一个正值，让高光躲开 label 切口。
     */
    protected open fun topHighlightYInset(): Float = 0f

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildEdgePaths()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // boxBackground.bounds 在 super.onLayout 中赋值；这里再拿一次确保高光/阴影
        // 跟随实际盒子（而不是包含 helperText/errorText 的整个视图）。
        rebuildEdgePaths()
    }

    /**
     * 找输入盒子的边界：从 EditText 沿 parent 链上溯，直到 parent 就是 TextInputLayout —
     * 这一层就是 Material 内部的 inputFrame，它的 left/top/right/bottom 正好是 outlined box
     * 的边界（不包含 helperText / errorText / counter）。
     * 拿不到（极早期 layout）时返回 null，调用方走整 view 兜底。
     */
    private fun findBoxBounds(): android.graphics.Rect? {
        val edit = editText ?: return null
        var v: android.view.View = edit
        while (v.parent is android.view.View && v.parent !== this) {
            v = v.parent as android.view.View
        }
        if (v === edit && v.parent !== this) return null
        if (v.width <= 0 || v.height <= 0) return null
        return android.graphics.Rect(v.left, v.top, v.right, v.bottom)
    }

    private fun rebuildEdgePaths() {
        // 跟随实际盒子，避免 helperText/errorText 把阴影推到下面。
        val boxBounds = findBoxBounds()
        val left: Float
        val top: Float
        val right: Float
        val bottom: Float
        if (boxBounds != null) {
            left = boxBounds.left + edgeInset
            top = boxBounds.top + edgeInset
            right = boxBounds.right - edgeInset
            bottom = boxBounds.bottom - edgeInset
        } else {
            left = edgeInset
            top = edgeInset
            right = (width - edgeInset).coerceAtLeast(edgeInset)
            bottom = (height - edgeInset).coerceAtLeast(edgeInset)
        }
        frame.set(left, top, right, bottom)
        val r = cornerRadius.coerceAtMost(frame.height() / 2f)
        // 顶部高光向下偏移；让带浮动 label 的变体可以把高光让出来，避开 label 切口。
        val topInset = topHighlightYInset().coerceAtLeast(0f)
        val topY = frame.top + topInset
        topPath.reset()
        // Upper outline: left-bottom of top-left arc → top → right-bottom of top-right arc.
        topPath.moveTo(frame.left, topY + r)
        topPath.arcTo(frame.left, topY, frame.left + 2 * r, topY + 2 * r,
            180f, 90f, false)
        topPath.lineTo(frame.right - r, topY)
        topPath.arcTo(frame.right - 2 * r, topY, frame.right, topY + 2 * r,
            270f, 90f, false)

        bottomPath.reset()
        bottomPath.moveTo(frame.left, frame.bottom - r)
        bottomPath.arcTo(frame.left, frame.bottom - 2 * r, frame.left + 2 * r, frame.bottom,
            180f, -90f, false)
        bottomPath.lineTo(frame.right - r, frame.bottom)
        bottomPath.arcTo(frame.right - 2 * r, frame.bottom - 2 * r, frame.right, frame.bottom,
            90f, -90f, false)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        canvas.drawPath(topPath, highlightPaint)
        canvas.drawPath(bottomPath, shadowPaint)
    }

    protected fun dp(value: Float): Float = value * resources.displayMetrics.density
}
