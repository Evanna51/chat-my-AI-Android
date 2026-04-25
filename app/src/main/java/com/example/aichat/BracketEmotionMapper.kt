package com.example.aichat

/**
 * 通用情绪/调音 profile，TTS 和 UI 共用。
 * 字段对应火山 V3 audio_params：emotion / emotion_scale / speech_rate / loudness_rate / pitch_rate。
 */
data class SpeechProfile(
    val emotion: String? = null,
    val emotionScale: Int? = null,
    val speechRate: Int? = null,
    val loudnessRate: Int? = null,
    val pitchRate: Int? = null,
) {
    fun hasAnyParam(): Boolean =
        emotion != null || emotionScale != null ||
            speechRate != null || loudnessRate != null || pitchRate != null
}

/**
 * 关键词 → SpeechProfile 的兜底匹配器。
 * 只在角色消息里没有协议 emoji（见 [EmotionTagParser]）时生效，用括号内文字猜情绪。
 */
object BracketEmotionMapper {

    // 长词在前，短词在后，避免短词把长词模式吞掉。
    private val keywordProfiles: List<Pair<Regex, SpeechProfile>> = listOf(
        // ---- 私密 / 暧昧 ----
        Regex("耳边|附耳|凑近|贴近") to SpeechProfile("whisper", 5, speechRate = -10, loudnessRate = -30),
        Regex("气声|喘息|喘|急促|呼吸") to SpeechProfile("whisper", 5, speechRate = -15, loudnessRate = -35, pitchRate = -1),
        Regex("轻声|小声|低声|呢喃|喃喃") to SpeechProfile("whisper", 4, speechRate = -10, loudnessRate = -25),
        Regex("暧昧|挑逗|诱惑|魅惑|勾人") to SpeechProfile("affectionate", 5, speechRate = -10, loudnessRate = -10),
        Regex("撒娇|嗲|娇|奶音") to SpeechProfile("lovey-dovey", 4, pitchRate = 1),
        Regex("害羞|羞涩|脸红|不好意思|忸怩") to SpeechProfile("shy", 4, speechRate = -5, loudnessRate = -15),
        // ---- 温柔 / 关怀 ----
        Regex("温柔|柔和|轻柔|宠溺|怜爱") to SpeechProfile("tenderness", 4, speechRate = -5),
        Regex("深情|认真地|郑重|凝视") to SpeechProfile("affectionate", 4),
        Regex("安慰|安抚|哄|安心") to SpeechProfile("comfort", 4, speechRate = -5),
        Regex("叹气|叹息|无奈|苦笑") to SpeechProfile("sad", 3, speechRate = -10),
        // ---- 情绪 ----
        Regex("大笑|哈哈|爽朗") to SpeechProfile("happy", 5),
        Regex("轻笑|莞尔|微笑|笑着|笑了") to SpeechProfile("happy", 3),
        Regex("兴奋|激动|雀跃|欣喜") to SpeechProfile("excited", 5),
        Regex("难过|悲伤|失落|哽咽|哭") to SpeechProfile("sad", 4, speechRate = -10),
        Regex("生气|愤怒|怒|不悦|恼") to SpeechProfile("angry", 4),
        Regex("惊讶|吃惊|愕然|诧异|意外") to SpeechProfile("surprised", 4),
        Regex("紧张|忐忑|不安|紧绷") to SpeechProfile("tension", 4),
        Regex("冷漠|冷淡|淡漠|平静") to SpeechProfile("coldness", 3),
        Regex("沮丧|低落|失望") to SpeechProfile("depressed", 3),
        Regex("讲故事|娓娓道来|叙述|缓缓") to SpeechProfile("storytelling", 3, speechRate = -5),
    )

    /** 从一段括号内文字（已剥括号）匹配第一个命中的 profile，没命中返回 null。 */
    fun matchByKeyword(bracketContent: String): SpeechProfile? {
        if (bracketContent.isEmpty()) return null
        for ((pattern, profile) in keywordProfiles) {
            if (pattern.containsMatchIn(bracketContent)) return profile
        }
        return null
    }
}
