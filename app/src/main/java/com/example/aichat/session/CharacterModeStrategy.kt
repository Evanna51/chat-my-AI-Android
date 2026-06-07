package com.example.aichat.session

import com.example.aichat.EmotionTagParser
import com.example.aichat.Message
import com.example.aichat.VolcEngineHttpTTS

/**
 * 角色 / 人物对话模式。
 *
 * 与默认模式的差异：
 * - Adapter 走 character 渲染（括号情绪染色 + 角色头像）
 * - 助手 reasoning 折叠开关被禁用（角色对话强制展示完整内容）
 * - 不自动聚焦最新消息（角色对话有时停留在中段阅读）
 * - 不显示「悬浮工具栏」，pinned actions hidden（角色对话不需要 regenerate/edit 等动作打断沉浸）
 * - 支持自动 TTS
 * - 朗读时用 [EmotionTagParser] 解析括号情绪 → 提取 ttsText + SpeechParams
 */
object CharacterModeStrategy : SessionModeStrategy {
    override val mode: SessionMode = SessionMode.CHARACTER
    override val usesCharacterAdapter: Boolean = true
    override val usesWriterAdapter: Boolean = false
    override val disablesAssistantCollapseToggle: Boolean = true
    override val autoFocusLatestOnSetMessages: Boolean = false
    override val hidesPinnedActions: Boolean = true
    override val showsWriterOutlineButton: Boolean = false
    override val supportsAutoTts: Boolean = true
    override val supportsOutlineExport: Boolean = false

    override fun resolveVoicePlay(message: Message, raw: String): VoicePlayPayload {
        val parsed = EmotionTagParser.parse(raw)
        val profile = parsed.profile
        val speechParams = if (profile != null && profile.hasAnyParam()) {
            VolcEngineHttpTTS.SpeechParams(
                emotion = profile.emotion,
                emotionScale = profile.emotionScale,
                speechRate = profile.speechRate,
                loudnessRate = profile.loudnessRate,
                pitchRate = profile.pitchRate,
            )
        } else {
            null
        }
        return VoicePlayPayload(parsed.ttsText, speechParams)
    }
}
