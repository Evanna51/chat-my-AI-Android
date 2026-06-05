package com.example.aichat.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * 3 个圆点的"对方正在输入"动画指示器. 每个点按正弦半波(向上跳一下)循环,
 * 相邻点之间相位错开 ~1/5 周期, 视觉上像三个点依次"跳一跳".
 *
 * 自驱动: onAttachedToWindow / onVisibilityChanged 自动起停 ValueAnimator,
 * 上层只管 visibility, 无需手动 start/stop.
 */
class TypingDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC8E8E93.toInt()
        style = Paint.Style.FILL
    }

    private val dotCount = 3
    private val dotRadiusPx: Float = dp(1.8f)
    private val dotSpacingPx: Float = dp(3f)
    private val bounceAmpPx: Float = dp(2f)
    private val cycleMs: Long = 1000L
    /** 相邻点的相位差(占整个周期的比例). 0.18 ≈ 180ms, 三点依次跳 + 短暂留白. */
    private val stagger: Float = 0.18f

    private var phase: Float = 0f
    private var animator: ValueAnimator? = null

    fun setColor(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = (dotRadiusPx * 2 * dotCount +
            dotSpacingPx * (dotCount - 1) +
            paddingLeft + paddingRight).toInt()
        val desiredH = (dotRadiusPx * 2 + bounceAmpPx +
            paddingTop + paddingBottom).toInt()
        setMeasuredDimension(
            resolveSize(desiredW, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val baseCy = paddingTop + bounceAmpPx + dotRadiusPx
        var cx = paddingLeft + dotRadiusPx
        for (i in 0 until dotCount) {
            val p = (phase + i * stagger) % 1f
            // 整周期一次 sin(2π·p), 取正半波即"跳一下", 负半波保持原位.
            val hop = sin(p * 2.0 * PI).toFloat().coerceAtLeast(0f)
            val y = baseCy - hop * bounceAmpPx
            canvas.drawCircle(cx, y, dotRadiusPx, paint)
            cx += dotRadiusPx * 2 + dotSpacingPx
        }
    }

    private fun start() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = cycleMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) start() else stop()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
