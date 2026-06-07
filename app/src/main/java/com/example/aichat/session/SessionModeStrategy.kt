package com.example.aichat.session

import com.example.aichat.Message
import com.example.aichat.MyAssistant
import com.example.aichat.VolcEngineHttpTTS

/**
 * 会话模式的策略接口。把原来散落在 ChatSessionActivity 里的
 * `if (writerAssistant)` / `if (characterAssistant)` 分支按模式收口
 * 到独立 strategy 对象，每个 strategy 是单例 `object`（无状态）。
 *
 * 设计：
 * - **声明式属性**（usesXxx / supportsXxx 等 val）暴露给 Activity 应用 UI 配置
 * - **行为钩子**（buildUserMessageForApi 等 fun）在 strategy 内实现具体差异
 * - **唯一允许 `when (mode)` 的地方**是 [SessionModeStrategy.from] 工厂；
 *   其它任何地方出现 `when (mode)` / `if (mode.is...)` 都是退步
 *
 * 添加新模式 = **新建一个 strategy 文件 + 加一支 from() 分支**，
 * **零修改现有 strategy**。
 */
interface SessionModeStrategy {

    val mode: SessionMode

    // ─────────── 声明式 UI 配置 ───────────

    /** adapter.setCharacterMode(this)：是否走括号情绪渲染 + character 头像 */
    val usesCharacterAdapter: Boolean

    /** adapter.setWriterMode(this)：是否走 writer 文本格式 */
    val usesWriterAdapter: Boolean

    /** adapter.setDisableAssistantCollapseToggle(this)：character 不允许折叠 */
    val disablesAssistantCollapseToggle: Boolean

    /** adapter.setAutoFocusLatestOnSetMessages(!this)：character 模式关闭自动聚焦 */
    val autoFocusLatestOnSetMessages: Boolean

    /** character 模式下不显示「最新消息的悬浮工具栏」*/
    val hidesPinnedActions: Boolean

    /** writer 模式才显示 toolbar 的 outline 按钮 */
    val showsWriterOutlineButton: Boolean


    /** character 模式才支持自动 TTS 朗读 */
    val supportsAutoTts: Boolean

    /** writer 模式才支持「仅大纲 / 仅助手」等更细粒度导出范围；其它模式只导出全部对话 */
    val supportsOutlineExport: Boolean

    // ─────────── 行为钩子 ───────────

    /**
     * 发消息前最后一次改写用户输入。
     * - writer 模式：注入大纲块到末尾
     * - 其它模式：原样返回
     */
    fun buildUserMessageForApi(rawInput: String, ctx: SessionContext): String = rawInput

    /**
     * 上传给模型的历史消息列表加工。
     * - writer 模式：长助手消息做节选（前段/中段/后段或前 N 字）
     * - 其它模式：原样返回
     */
    fun buildHistoryForApi(source: List<Message>, ctx: SessionContext): List<Message> = source

    /**
     * 单条消息长按 → 「转大纲」action。
     * - writer 模式：调 host.summarizeMessageToOutline 并返回 true
     * - 其它模式：返回 false（Activity 走默认 = 不响应）
     */
    fun onOutlineAction(message: Message, host: SessionUiHost): Boolean = false

    /**
     * 朗读按钮被点击时，解析消息要朗读的纯文本 + TTS 参数。
     * - character 模式：用 EmotionTagParser 解 `[emotion]` 协议
     * - 其它模式：返回 null = 调用方走「原样朗读 + 默认参数」
     */
    fun resolveVoicePlay(message: Message, raw: String): VoicePlayPayload? = null

    companion object {
        /**
         * 工厂入口。**唯一允许 when(SessionMode) 的地方**。
         * 添加新模式时，只在这里加一支 case。
         */
        fun from(assistant: MyAssistant?): SessionModeStrategy {
            return when (com.example.aichat.session.from(assistant)) {
                SessionMode.CHARACTER -> CharacterModeStrategy
                SessionMode.WRITER -> WriterModeStrategy
                SessionMode.DEFAULT -> DefaultModeStrategy
            }
        }
    }
}

/** 角色 TTS 的解析结果。`speechParams` 为 null 时调用方用默认 TTS 参数。*/
data class VoicePlayPayload(
    val text: String,
    val speechParams: VolcEngineHttpTTS.SpeechParams?,
)

/** Internal: SessionMode 解析；与 MyAssistant?.mode() 等价，只是放在 session 包内方便用。*/
internal fun from(assistant: MyAssistant?): SessionMode {
    return SessionMode.from(assistant?.type)
}
