package com.example.aichat.sync

/** Single conversation turn pushed to the server. */
data class SyncTurnDto(
    val id: String,            // client-generated UUID v7
    val assistantId: String,
    val sessionId: String,
    val role: String,          // "user" | "assistant"
    val content: String,
    val createdAt: Long
)

data class SyncPushRequest(
    val deviceId: String,
    val turns: List<SyncTurnDto>
)

data class SyncPushDetail(
    val id: String,
    val status: String,        // "accepted" | "skipped" | "rejected"
    val reason: String? = null
)

data class SyncPushResponse(
    val ok: Boolean = false,
    val deviceId: String? = null,
    val accepted: Int = 0,
    val skipped: Int = 0,
    val rejected: Int = 0,
    val details: List<SyncPushDetail> = emptyList(),
    val cancelledPlans: Int = 0,
    val error: String? = null
)

/** Assistant 元数据快照, 一次性同步时随 turns 一起上传. */
data class AssistantSnapshotDto(
    val assistantId: String,
    val characterName: String,
    /** 系统 prompt; 对真实 Assistant 取 options.systemPrompt, fallback assistant 留空. */
    val characterBackground: String,
    val allowAutoLife: Boolean,
    val allowProactiveMessage: Boolean
)

data class SnapshotPushRequest(
    val deviceId: String,
    val assistants: List<AssistantSnapshotDto>,
    val turns: List<SyncTurnDto>
)

data class SyncStateResponse(
    val ok: Boolean = false,
    val now: Long = 0,
    val assistantId: String? = null,
    val deviceId: String? = null,
    val assistantTurnCount: Int? = null,
    val totalTurnCount: Int = 0,
    val lastTurnAt: Long? = null,
    val error: String? = null
)
