package com.example.aichat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message",
    indices = [
        Index(value = ["synced", "createdAt"], name = "idx_message_pending_sync"),
    ],
)
class Message {

    companion object {
        @JvmField val ROLE_USER = 0
        @JvmField val ROLE_ASSISTANT = 1
        @JvmField val ROLE_SYSTEM = 2
        // Assistant turn that emits tool_calls (content 为空, toolCallsJson 装 OpenAI 风格 tool_calls 数组).
        @JvmField val ROLE_TOOL_CALL = 3
        // Single tool execution result (toolCallId + toolName + content=结果 JSON).
        @JvmField val ROLE_TOOL_RESULT = 4
    }

    @PrimaryKey(autoGenerate = true)
    @JvmField
    var id: Long = 0

    @JvmField
    var sessionId: String = ""

    @JvmField
    var role: Int = 0

    @JvmField
    var content: String = ""

    @JvmField
    var createdAt: Long = 0

    @JvmField
    var reasoning: String = ""

    @JvmField
    var thinkingElapsedMs: Long = 0

    @JvmField
    @ColumnInfo(defaultValue = "''")
    var embedding: String = ""

    /** UUID v7, client-generated for sync. Empty = legacy / not eligible for remote sync. */
    @JvmField
    @ColumnInfo(defaultValue = "''")
    var turnId: String = ""

    /** Snapshot of bound assistantId at insert time. Empty = no assistant bound. */
    @JvmField
    @ColumnInfo(defaultValue = "''")
    var assistantId: String = ""

    /** 0 = pending push, 1 = pushed (or skipped via already_exists). */
    @JvmField
    @ColumnInfo(defaultValue = "0")
    var synced: Int = 0

    @JvmField
    @ColumnInfo(defaultValue = "0")
    var syncAttempts: Int = 0

    @JvmField
    var lastAttemptAt: Long? = null

    @JvmField
    @ColumnInfo(defaultValue = "''")
    var lastError: String = ""

    /**
     * 仅 role=ROLE_TOOL_CALL 行使用：序列化的 OpenAI tool_calls 数组
     * (e.g. `[{"id":"call_xxx","type":"function","function":{"name":..., "arguments":...}}]`).
     * 其它 role 留空。
     */
    @JvmField
    @ColumnInfo(defaultValue = "''")
    var toolCallsJson: String = ""

    /** 仅 role=ROLE_TOOL_RESULT 行使用：对应 assistant 行 tool_calls 里的 id (用于 server 回查). */
    @JvmField
    @ColumnInfo(defaultValue = "''")
    var toolCallId: String = ""

    /** 仅 role=ROLE_TOOL_RESULT 行使用：被调用的 tool 名 (e.g. `search_memory`). */
    @JvmField
    @ColumnInfo(defaultValue = "''")
    var toolName: String = ""

    /**
     * 自动对话标记 (Phase 1):
     *   0 = 普通消息（用户/助手/工具行）
     *   1 = 由模型 META.split 拆出的分段（同一轮回复内的"打字模拟"消息）
     *   2 = 沉默期 follow-up 触发的主动消息
     * 客户端用于审计 / 限速 / 后续分析；不上传 server。
     */
    @JvmField
    @ColumnInfo(defaultValue = "0")
    var proactiveKind: Int = 0

    @Ignore
    @JvmField
    var promptTokens: Int = 0

    @Ignore
    @JvmField
    var completionTokens: Int = 0

    @Ignore
    @JvmField
    var totalTokens: Int = 0

    @Ignore
    @JvmField
    var elapsedMs: Long = 0

    @Ignore
    @JvmField
    var thinkingRunning: Boolean = false

    @Ignore
    @JvmField
    var thinkingStartedAt: Long = 0

    constructor()

    @Ignore
    constructor(sessionId: String, role: Int, content: String) {
        this.sessionId = sessionId
        this.role = role
        this.content = content
        this.createdAt = System.currentTimeMillis()
    }
}
