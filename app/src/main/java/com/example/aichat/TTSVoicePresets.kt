package com.example.aichat

/**
 * 火山引擎 TTS 音色预设。选中预设时会同步把 [resourceId] 填入 ResourceID 输入框。
 *
 * 通道对应关系：
 * - emo_v2_mars_bigtts（情感）/ moon_bigtts（经典）→ `volc.service_type.10029`（语音合成大模型）
 * - uranus_bigtts（大模型 2.0）→ `seed-tts-2.0`
 * - 声音复刻 → `volc.megatts.default`
 *
 * 这里只列了角色对话场景比较合用的几个；用户也可以直接在输入框里手输自定义 speaker。
 */
data class TTSVoicePreset(
    val label: String,
    val speakerId: String,
    val resourceId: String,
) {
    /** dropdown 列表中显示的字符串。 */
    val display: String get() = "$label  ·  $speakerId"
}

object TTSVoicePresets {

    private const val RES_BIG = "volc.service_type.10029"
    private const val RES_SEED_TTS_2 = "seed-tts-2.0"

    val all: List<TTSVoicePreset> = listOf(
        // ---- 公版情感音色（emo_v2，支持 emotion 参数）----
        TTSVoicePreset("如雅亦辰（男 · 情感）", "zh_male_ruyayichen_emo_v2_mars_bigtts", RES_BIG),
        TTSVoicePreset("冷酷哥哥（男 · 情感）", "zh_male_lengkugege_emo_v2_mars_bigtts", RES_BIG),
        TTSVoicePreset("柔美女友（女 · 情感）", "zh_female_roumeinvyou_emo_v2_mars_bigtts", RES_BIG),
        TTSVoicePreset("爽快思思（女 · 情感）", "zh_female_shuangkuaisisi_emo_v2_mars_bigtts", RES_BIG),
        TTSVoicePreset("邻家女孩（女 · 情感）", "zh_female_linjianvhai_emo_v2_mars_bigtts", RES_BIG),
        // ---- 大模型 2.0（uranus，模型自动推情绪，走 seed-tts-2.0 通道）----
        TTSVoicePreset("微微（女 · 大模型 2.0）", "zh_female_vv_uranus_bigtts", RES_SEED_TTS_2),
        TTSVoicePreset("如雅亦辰（男 · 大模型 2.0）", "zh_male_ruyayichen_uranus_bigtts", RES_SEED_TTS_2),
        // ---- 经典 moon 系列 ----
        TTSVoicePreset("爽快思思（女 · 经典）", "zh_female_shuangkuaisisi_moon_bigtts", RES_BIG),
    )

    fun findBySpeakerId(speakerId: String?): TTSVoicePreset? {
        if (speakerId.isNullOrEmpty()) return null
        return all.firstOrNull { it.speakerId == speakerId }
    }
}

/**
 * 火山引擎 TTS X-Api-Resource-Id 常用值。
 * 对应不同的服务通道；和 speaker 必须配套（公版音色/声音复刻/双向流式各走不同通道）。
 */
data class TTSResourcePreset(
    val resourceId: String,
    val description: String,
) {
    val display: String get() = "$resourceId  ·  $description"
}

object TTSResourcePresets {
    val all: List<TTSResourcePreset> = listOf(
        TTSResourcePreset("volc.service_type.10029", "语音合成大模型（公版音色）"),
        TTSResourcePreset("volc.megatts.default", "声音复刻大模型"),
        TTSResourcePreset("seed-tts-2.0", "Seed TTS 2.0（双向流式）"),
        TTSResourcePreset("seed-tts-1.0", "Seed TTS 1.0（双向流式）"),
        TTSResourcePreset("seed-icl-2.0", "声音复刻 ICL 2.0（字符版）"),
        TTSResourcePreset("seed-icl-1.0", "声音复刻 ICL 1.0（字符版）"),
        TTSResourcePreset("seed-icl-1.0-concurr", "声音复刻 ICL 1.0（并发版）"),
    )
}
