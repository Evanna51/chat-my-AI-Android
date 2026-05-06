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
    @JvmField var proactiveResetDate: Int = 0
)
