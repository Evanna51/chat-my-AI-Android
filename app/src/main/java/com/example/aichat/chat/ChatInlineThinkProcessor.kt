package com.example.aichat.chat

import java.util.Locale

/**
 * Splits streaming content deltas that contain inline `<think>...</think>`
 * markers (emitted by Qwen3 / DeepSeek-R1-style models regardless of
 * provider) into a content-channel and a reasoning-channel.
 *
 * Pulled out of ChatService.kt as part of Phase B refactor; behavior is
 * unchanged. The state object MUST be created once per stream round and
 * threaded through every [splitInlineThink] call so that tags spanning
 * delta boundaries (`</thi` + `nk>` arriving in separate chunks) are
 * handled correctly.
 */
class InlineThinkState {
    var inThink: Boolean = false
    var carry: String = ""
}

/** Result of one [InlineThinkProcessor.splitInlineThink] call. */
class ContentReasoningParts(content: String?, reasoning: String?) {
    @JvmField val content: String = content ?: ""
    @JvmField val reasoning: String = reasoning ?: ""
}

object InlineThinkProcessor {

    /**
     * Whether the SSE stream parser should run inline-think normalization for
     * this provider. Currently always returns true: [splitInlineThink] is a
     * no-op when no `<think>` tag appears, so enabling it everywhere is safe
     * and avoids missing tags from misclassified providers.
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldNormalize(providerId: String?): Boolean = true

    /**
     * Process one streaming content delta. Returns the visible content half
     * and the reasoning (think) half. Pass `flushTail = true` once at the
     * end of the stream to drain any remaining buffered carry.
     */
    fun splitInlineThink(
        delta: String?,
        state: InlineThinkState?,
        flushTail: Boolean,
    ): ContentReasoningParts {
        if (state == null) {
            return ContentReasoningParts(delta ?: "", "")
        }
        val chunk = delta ?: ""
        val input = state.carry + chunk
        state.carry = ""
        if (input.isEmpty()) return ContentReasoningParts("", "")

        val carryLen = if (flushTail) 0 else computeThinkTagCarry(input)
        val parse = input.substring(0, input.length - carryLen)
        if (!flushTail && carryLen > 0) {
            state.carry = input.substring(input.length - carryLen)
        }

        val outContent = StringBuilder()
        val outReasoning = StringBuilder()
        var i = 0
        val openTag = "<think>"
        val closeTag = "</think>"
        while (i < parse.length) {
            if (state.inThink) {
                val close = indexOfIgnoreCase(parse, closeTag, i)
                if (close < 0) {
                    outReasoning.append(parse.substring(i))
                    i = parse.length
                } else {
                    outReasoning.append(parse, i, close)
                    i = close + closeTag.length
                    state.inThink = false
                }
            } else {
                val open = indexOfIgnoreCase(parse, openTag, i)
                if (open < 0) {
                    outContent.append(parse.substring(i))
                    i = parse.length
                } else {
                    outContent.append(parse, i, open)
                    i = open + openTag.length
                    state.inThink = true
                }
            }
        }

        if (flushTail && state.carry.isNotEmpty()) {
            if (state.inThink) outReasoning.append(state.carry)
            else outContent.append(state.carry)
            state.carry = ""
        }
        return ContentReasoningParts(outContent.toString(), outReasoning.toString())
    }

    /**
     * Length (1..tagLen-1) of the longest tag prefix at the END of [input],
     * so the parser knows how much trailing text to hold over to the next
     * delta. Returns 0 when no partial tag is in flight.
     */
    private fun computeThinkTagCarry(input: String?): Int {
        if (input == null || input.isEmpty()) return 0
        val lower = input.lowercase(Locale.ROOT)
        val tags = arrayOf("<think>", "</think>")
        var best = 0
        for (tag in tags) {
            for (len in 1 until tag.length) {
                if (lower.endsWith(tag.substring(0, len))) {
                    if (len > best) best = len
                }
            }
        }
        return best
    }

    private fun indexOfIgnoreCase(text: String?, needle: String?, fromIndex: Int): Int {
        if (text == null || needle == null) return -1
        val lowerText = text.lowercase(Locale.ROOT)
        val lowerNeedle = needle.lowercase(Locale.ROOT)
        return lowerText.indexOf(lowerNeedle, kotlin.math.max(0, fromIndex))
    }
}
