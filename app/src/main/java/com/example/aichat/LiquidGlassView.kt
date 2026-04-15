package com.example.aichat

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // --- Configurable parameters ---
    private var blurRadius: Float = 25f.dpToPx()
    private var saturation: Float = 1.5f
    private var brightnessOffset: Float = 0.05f
    @ColorInt private var tintColor: Int = 0
    @ColorInt private var strokeColor: Int = 0
    private var strokeWidth: Float = 1f.dpToPx()
    private var cornerRadius: Float = 16f.dpToPx()
    private var edgeHighlightEnabled: Boolean = true
    private var dynamicResponseEnabled: Boolean = false

    // --- Paints ---
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val boundsRect = RectF()
    private val isDarkMode: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    init {
        // Read custom attributes
        val ta = context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassView, defStyleAttr, 0)
        blurRadius = ta.getDimension(R.styleable.LiquidGlassView_glassBlurRadius, if (isDarkMode) 20f.dpToPx() else 25f.dpToPx())
        saturation = ta.getFloat(R.styleable.LiquidGlassView_glassSaturation, if (isDarkMode) 1.2f else 1.5f)
        brightnessOffset = ta.getFloat(R.styleable.LiquidGlassView_glassBrightnessOffset, if (isDarkMode) -0.03f else 0.05f)
        tintColor = ta.getColor(R.styleable.LiquidGlassView_glassTintColor, ContextCompat.getColor(context, R.color.glass_surface))
        strokeColor = ta.getColor(R.styleable.LiquidGlassView_glassStrokeColor, ContextCompat.getColor(context, R.color.glass_stroke))
        strokeWidth = ta.getDimension(R.styleable.LiquidGlassView_glassStrokeWidth, 1f.dpToPx())
        cornerRadius = ta.getDimension(R.styleable.LiquidGlassView_glassCornerRadius, 16f.dpToPx())
        edgeHighlightEnabled = ta.getBoolean(R.styleable.LiquidGlassView_glassEdgeHighlight, true)
        dynamicResponseEnabled = ta.getBoolean(R.styleable.LiquidGlassView_glassDynamicResponse, false)
        ta.recycle()

        // Enable drawing
        setWillNotDraw(false)
        // Clip children to rounded corners
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }

        setupTier()
    }

    private fun setupTier() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Tier 1: API 31+ — apply RenderEffect blur to background
            setupTier1()
        }
        // Tier 2 & 3: handled in onDraw
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.S)
    private fun setupTier1() {
        // RenderEffect blur is applied to the view's background rendering
        // We create a blur effect that will be applied via setRenderEffect on a background view if needed
        // For now, the blur is visual only - the actual RenderEffect needs a background bitmap
        // This is set up for integration when LiquidGlassView wraps content
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        boundsRect.set(0f, 0f, w.toFloat(), h.toFloat())
        updateHighlightShader()
        updateShadowShader()
    }

    private fun updateHighlightShader() {
        if (!edgeHighlightEnabled || height == 0) return
        val highlightHeight = height * 0.35f  // 30-40% of height
        val topColor = ContextCompat.getColor(context,
            if (isDarkMode) R.color.glass_highlight_top else R.color.glass_highlight_top)
        highlightPaint.shader = LinearGradient(
            0f, 0f, 0f, highlightHeight,
            intArrayOf(topColor, Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
    }

    private fun updateShadowShader() {
        if (!edgeHighlightEnabled || height == 0) return
        val shadowHeight = height * 0.18f  // 15-20% of height
        val bottomColor = ContextCompat.getColor(context,
            if (isDarkMode) R.color.glass_shadow_bottom else R.color.glass_shadow_bottom)
        shadowPaint.shader = LinearGradient(
            0f, height - shadowHeight, 0f, height.toFloat(),
            intArrayOf(Color.TRANSPARENT, bottomColor),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        // Layer 1+2+3: Surface (tint + saturation simulation for Tier 2/3)
        surfacePaint.color = tintColor
        canvas.drawRoundRect(boundsRect, cornerRadius, cornerRadius, surfacePaint)

        // Apply saturation/brightness via ColorMatrix for Tier 2
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val matrix = ColorMatrix()
            matrix.setSaturation(saturation)
            val brightnessMatrix = ColorMatrix(floatArrayOf(
                1f + brightnessOffset, 0f, 0f, 0f, 0f,
                0f, 1f + brightnessOffset, 0f, 0f, 0f,
                0f, 0f, 1f + brightnessOffset, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            matrix.postConcat(brightnessMatrix)
            tintPaint.colorFilter = ColorMatrixColorFilter(matrix)
            tintPaint.color = tintColor
            canvas.drawRoundRect(boundsRect, cornerRadius, cornerRadius, tintPaint)
        }

        // Layer 4: Edge highlight
        if (edgeHighlightEnabled) {
            // Top highlight
            canvas.drawRoundRect(boundsRect, cornerRadius, cornerRadius, highlightPaint)
            // Bottom shadow
            canvas.drawRoundRect(boundsRect, cornerRadius, cornerRadius, shadowPaint)
        }

        // Stroke
        strokePaint.color = strokeColor
        strokePaint.strokeWidth = strokeWidth
        val inset = strokeWidth / 2f
        val strokeRect = RectF(boundsRect).apply { inset(inset, inset) }
        canvas.drawRoundRect(strokeRect, cornerRadius, cornerRadius, strokePaint)
    }

    // --- Public API for dynamic response ---

    fun setBlurRadius(radius: Float) {
        blurRadius = radius.dpToPx()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Update RenderEffect if using Tier 1
            invalidate()
        }
    }

    fun setHighlightAlpha(alpha: Float) {
        highlightPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        invalidate()
    }

    fun setGlassTintColor(@ColorInt color: Int) {
        tintColor = color
        invalidate()
    }

    fun animateBlurRadius(from: Float, to: Float, duration: Long = 200) {
        android.animation.ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { setBlurRadius(it.animatedValue as Float) }
            start()
        }
    }

    fun animateTintAlpha(from: Float, to: Float, duration: Long = 200) {
        android.animation.ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val alpha = (it.animatedValue as Float * 255).toInt().coerceIn(0, 255)
                tintColor = (tintColor and 0x00FFFFFF) or (alpha shl 24)
                invalidate()
            }
            start()
        }
    }

    fun animateBrightness(from: Float, to: Float, duration: Long = 150) {
        android.animation.ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            addUpdateListener {
                brightnessOffset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // --- Utility ---
    private fun Float.dpToPx(): Float = this * context.resources.displayMetrics.density
}
