package com.example.aichat

/**
 * 角色消息文本解析。约定见角色 system prompt：
 *  - `(...)` / `（...）` 等括号包裹的是动作/心理/旁白 → TTS 跳过、UI 灰色显示
 *  - 协议 emoji（🤫😘🥺😳🥰😊😢😌🥵）放在台词段开头表示情绪 → TTS 取标签设 emotion，UI 隐藏
 * 解析输出同时给 TTS（[ttsText] + [profile]）和 UI（[displayText] + [narrationRanges]）使用。
 */
object EmotionTagParser {

    data class Parsed(
        val ttsText: String,
        val displayText: String,
        val narrationRanges: List<IntRange>, // [start, endInclusive) 半开区间，作用于 displayText
        val profile: SpeechProfile?,
    )

    /** emoji → 情绪 profile。顺序无关，但放在前面的会优先（最先出现位置相同时） */
    private val emojiProfiles: List<Pair<String, SpeechProfile>> = listOf(
        "🤫" to SpeechProfile("whisper", 4, speechRate = -10, loudnessRate = -25),
        "😘" to SpeechProfile("affectionate", 5, loudnessRate = -10),
        "🥺" to SpeechProfile("lovey-dovey", 4, pitchRate = 1),
        "😳" to SpeechProfile("shy", 4, speechRate = -5, loudnessRate = -15),
        "🥰" to SpeechProfile("tenderness", 4, speechRate = -5),
        "😊" to SpeechProfile("happy", 3),
        "😢" to SpeechProfile("sad", 4, speechRate = -10),
        "😌" to SpeechProfile("comfort", 4, speechRate = -5),
        "🥵" to SpeechProfile("affectionate", 5, speechRate = -10, loudnessRate = -10),
    )

    private val bracketRegex =
        Regex("[（(\\[【\\{][^）)\\]】\\}]*[）)\\]】\\}]")

    fun parse(source: String): Parsed {
        if (source.isEmpty()) {
            return Parsed("", "", emptyList(), null)
        }

        // 1) 找出消息里第一个协议 emoji，决定情绪 profile
        val firstEmoji = emojiProfiles
            .mapNotNull { (emoji, profile) ->
                val idx = source.indexOf(emoji)
                if (idx >= 0) Triple(idx, emoji, profile) else null
            }
            .minByOrNull { it.first }

        val emojiProfile = firstEmoji?.third

        // 2) 删除所有协议 emoji，得到「显示文本」
        var displayBuilder = StringBuilder(source)
        for ((emoji, _) in emojiProfiles) {
            var idx = displayBuilder.indexOf(emoji)
            while (idx >= 0) {
                displayBuilder.delete(idx, idx + emoji.length)
                idx = displayBuilder.indexOf(emoji, idx)
            }
        }
        val displayText = displayBuilder.toString()

        // 3) 在 displayText 中找出所有括号区段的位置（给 UI 上 span 用）
        val narrationRanges = bracketRegex.findAll(displayText)
            .map { it.range.first..it.range.last }
            .toList()

        // 4) 朗读文本 = displayText 删除括号 + 折叠空白
        val ttsText = displayText
            .replace(bracketRegex, "")
            .replace(Regex("\\s+"), " ")
            .trim()

        // 5) 情绪 profile：emoji 优先，否则用第一个括号关键词 fallback
        val profile = emojiProfile ?: firstBracketKeywordProfile(displayText)

        return Parsed(
            ttsText = ttsText,
            displayText = displayText,
            narrationRanges = narrationRanges,
            profile = profile,
        )
    }

    private fun firstBracketKeywordProfile(text: String): SpeechProfile? {
        val firstBracket = bracketRegex.find(text)?.value ?: return null
        val inner = firstBracket
            .removePrefix("(").removePrefix("（").removePrefix("[").removePrefix("【").removePrefix("{")
            .removeSuffix(")").removeSuffix("）").removeSuffix("]").removeSuffix("】").removeSuffix("}")
            .trim()
        if (inner.isEmpty()) return null
        return BracketEmotionMapper.matchByKeyword(inner)
    }
}
