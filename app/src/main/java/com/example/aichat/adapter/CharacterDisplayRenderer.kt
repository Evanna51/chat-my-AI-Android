package com.example.aichat.adapter

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.aichat.EmotionTagParser
import com.example.aichat.R

/**
 * Character 模式下助手消息正文渲染：把 `[narration]` 段落染成次级文字色，
 * 其余主体保持默认颜色。anchor 仅用于取 Context（色资源）。
 *
 * 没有 narration 段时直接返回原字符串，避免不必要的 SpannableString 分配。
 */
object CharacterDisplayRenderer {
    fun render(anchor: TextView, content: String): CharSequence {
        if (content.isEmpty()) return content
        val parsed = EmotionTagParser.parse(content)
        val display = parsed.displayText
        if (parsed.narrationRanges.isEmpty()) return display
        val color = ContextCompat.getColor(anchor.context, R.color.ios_section_label)
        val span = SpannableString(display)
        for (range in parsed.narrationRanges) {
            val end = (range.last + 1).coerceAtMost(display.length)
            val start = range.first.coerceAtLeast(0)
            if (start >= end) continue
            span.setSpan(
                ForegroundColorSpan(color),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return span
    }
}
