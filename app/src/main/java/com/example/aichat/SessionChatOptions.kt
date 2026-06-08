package com.example.aichat

data class SessionChatOptions(
    @JvmField var sessionTitle: String = "",
    @JvmField var sessionAvatar: String = "",
    /** 会话级头像图片 base64。空字符串表示未覆盖，将回退到所绑定助手的头像。 */
    @JvmField var sessionAvatarImageBase64: String = "",
    @JvmField var contextMessageCount: Int = 6,
    @JvmField var modelKey: String = "",
    @JvmField var systemPrompt: String = "",
    @JvmField var temperature: Float = 0.7f,
    @JvmField var topP: Float = 1.0f,
    /** OpenAI/DeepSeek/Qwen 等通用扩展参数。null = 未设置，请求时按 ChatParamsResolver 链回退。 */
    @JvmField var maxTokens: Int? = null,
    @JvmField var frequencyPenalty: Float? = null,
    @JvmField var presencePenalty: Float? = null,
    /** Qwen / 部分本地模型支持；OpenAI 兼容路径会塞进 providerOptions。 */
    @JvmField var topK: Int? = null,
    @JvmField var stop: String = "",
    @JvmField var streamOutput: Boolean = true,
    @JvmField var autoChapterPlan: Boolean = false,
    @JvmField var thinking: Boolean = false,
    @JvmField var googleThinkingBudget: Int = 1024,
    /** 自动对话开关 (v10)。开启后 ChatService 注入 META 协议, 模型在回复尾部输出 split/followUp 元信息. */
    @JvmField var autoChatEnabled: Boolean = false,
    /** 当日已发起的主动消息数 (split + follow-up), 用于全局每日预算. */
    @JvmField var proactiveCountToday: Int = 0,
    /** 计数所属的"日期戳" (yyyymmdd 整型, 跨天自动重置 proactiveCountToday). */
    @JvmField var proactiveResetDate: Int = 0,
    /**
     * 每日上限. 0 = 走 [com.example.aichat.chat.ProactiveChatPlanner.DEFAULT_DAILY_BUDGET].
     * 用户可在会话设置里调整 (1..200; 太大也没意义, 角色不应轰炸用户).
     */
    @JvmField var proactiveDailyBudget: Int = 0,
    /**
     * 大纲生成时注入的额外 prompt（文风/风格指导等）。
     * 在 OutlinePromptBuilder 构建大纲上下文时追加到末尾，喂给模型辅助生成。
     * 对话配置和助手配置都可设置；对话级覆盖助手级。
     */
    @JvmField var outlinePrompt: String = "",
    /**
     * Writer 模式专属：每次章节输出结束后，由模型自动调 story tools 更新伏笔/状态/支线/感情线。
     * 开启后在 system prompt 末尾追加同步指令；关闭则不注入，tool call 全靠手动触发。
     */
    @JvmField var autoSyncStoryState: Boolean = false,
)
