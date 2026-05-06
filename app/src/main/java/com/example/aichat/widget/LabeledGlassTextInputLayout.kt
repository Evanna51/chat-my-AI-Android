package com.example.aichat.widget

import android.content.Context
import android.util.AttributeSet

/**
 * 带浮动标签的玻璃风输入框（OutlinedBox + 标题在顶上的变体）。
 *
 * 与 [GlassTextInputLayout] 的差别：
 * - 顶部高光向下偏移 2dp，让出 label 在顶 stroke 上的切口区域，
 *   避免高光被 label 文字盖住或重叠。
 * - 视觉契约保持一致：1dp 顶部高光 + 1dp 底部阴影 + 浅主题色边框（focused = colorPrimary）。
 *
 * 用法：通过 `Widget.AIChat.LabeledGlassInput` 样式，专门给「标题在顶上」的输入框使用。
 */
class LabeledGlassTextInputLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.textInputStyle,
) : GlassTextInputLayout(context, attrs, defStyleAttr) {

    override fun topHighlightYInset(): Float = dp(2f)
}
