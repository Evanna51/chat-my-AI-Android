package com.example.aichat.session

/**
 * 默认会话模式 —— 普通聊天，没有任何 writer/character 专属行为。
 * 所有属性都用「最常规」值，所有行为钩子用接口默认（passthrough）。
 */
object DefaultModeStrategy : SessionModeStrategy {
    override val mode: SessionMode = SessionMode.DEFAULT
    override val usesCharacterAdapter: Boolean = false
    override val usesWriterAdapter: Boolean = false
    override val disablesAssistantCollapseToggle: Boolean = false
    override val autoFocusLatestOnSetMessages: Boolean = true
    override val hidesPinnedActions: Boolean = false
    override val showsWriterOutlineButton: Boolean = false
    override val supportsAutoTts: Boolean = false
}
