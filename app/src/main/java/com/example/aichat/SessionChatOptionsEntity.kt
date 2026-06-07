package com.example.aichat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_chat_options")
class SessionChatOptionsEntity {

    @PrimaryKey
    @JvmField
    var sessionId: String = ""

    @JvmField
    var sessionTitle: String? = null

    @JvmField
    var sessionAvatar: String? = null

    @ColumnInfo(defaultValue = "")
    @JvmField
    var sessionAvatarImageBase64: String = ""

    @JvmField
    var modelKey: String? = null

    @JvmField
    var systemPrompt: String? = null

    @JvmField
    var stop: String? = null

    @ColumnInfo(defaultValue = "6")
    @JvmField
    var contextMessageCount: Int = 6

    @ColumnInfo(defaultValue = "1024")
    @JvmField
    var googleThinkingBudget: Int = 1024

    @ColumnInfo(defaultValue = "0.7")
    @JvmField
    var temperature: Float = 0.7f

    @ColumnInfo(defaultValue = "1.0")
    @JvmField
    var topP: Float = 1.0f

    /** v8 加入。null 表示未设置，请求时按 ChatParamsResolver 回退。 */
    @JvmField
    var maxTokens: Int? = null

    @JvmField
    var frequencyPenalty: Float? = null

    @JvmField
    var presencePenalty: Float? = null

    @JvmField
    var topK: Int? = null

    @ColumnInfo(defaultValue = "1")
    @JvmField
    var streamOutput: Boolean = true

    @ColumnInfo(defaultValue = "0")
    @JvmField
    var autoChapterPlan: Boolean = false

    @ColumnInfo(defaultValue = "0")
    @JvmField
    var thinking: Boolean = false

    /**
     * 自动对话开关 (v10). 为 true 时:
     *   - ChatService 注入 [自动对话模式] system 段, 让模型在回复尾部输出 META 块
     *   - ChatViewModel 解析 META 后调度 split / follow-up
     */
    @ColumnInfo(defaultValue = "0")
    @JvmField
    var autoChatEnabled: Boolean = false

    /** 当日已发起的主动消息数 (split + follow-up), 用于全局每日预算. */
    @ColumnInfo(defaultValue = "0")
    @JvmField
    var proactiveCountToday: Int = 0

    /** 计数所属的"日期戳" (yyyymmdd 整型, 跨天自动重置 proactiveCountToday). */
    @ColumnInfo(defaultValue = "0")
    @JvmField
    var proactiveResetDate: Int = 0

    /**
     * 每日上限. 0 = fall back to ProactiveChatPlanner.DEFAULT_DAILY_BUDGET.
     * v11 加入, 让用户自定义自动对话的每日发消息额度.
     */
    @ColumnInfo(defaultValue = "0")
    @JvmField
    var proactiveDailyBudget: Int = 0
}
