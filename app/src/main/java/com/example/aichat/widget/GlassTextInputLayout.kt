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
class GlassTextInputLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.textInputStyle,
) : TextInputLayout(context, attrs, defStyleAttr) {

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = ContextCompat.getColor(context, R.color.glass_input_highlight_top)
        strokeCap = Paint.Cap.ROUND
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = ContextCompat.getColor(context, R.color.glass_input_shadow_bottom)
        strokeCap = Paint.Cap.ROUND
    }
    private val topPath = Path()
    private val bottomPath = Path()
    private val frame = RectF()
    private val cornerRadius: Float = dp(14f)
    private val edgeInset: Float = dp(0.5f)

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildEdgePaths()
    }

    private fun rebuildEdgePaths() {
        // Match the box geometry by inset so the highlight sits flush to (but
        // does not overlap) the OutlinedBox stroke.
        frame.set(
            edgeInset,
            edgeInset,
            (width - edgeInset).coerceAtLeast(edgeInset),
            (height - edgeInset).coerceAtLeast(edgeInset),
        )
        val r = cornerRadius.coerceAtMost(frame.height() / 2f)
        topPath.reset()
        // Upper outline: left-bottom of top-left arc → top → right-bottom of top-right arc.
        topPath.moveTo(frame.left, frame.top + r)
        topPath.arcTo(frame.left, frame.top, frame.left + 2 * r, frame.top + 2 * r,
            180f, 90f, false)
        topPath.lineTo(frame.right - r, frame.top)
        topPath.arcTo(frame.right - 2 * r, frame.top, frame.right, frame.top + 2 * r,
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

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
