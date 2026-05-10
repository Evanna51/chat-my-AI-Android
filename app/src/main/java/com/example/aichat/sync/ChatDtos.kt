package com.example.aichat.sync

import com.google.gson.annotations.SerializedName

/**
 * DTOs for wi-chat-server v2 client-lifecycle endpoints (Phase 2).
 *
 * 取代关系（见 docs/api-redesign-plan.md §3）：
 *   GET  /api/character/{id}         ← /api/character/bootstrap
 *   POST /api/chat/context           ← /api/character/context + /api/tool/memory-context
 *   POST /api/chat/turn              ← /api/sync/push（语义化别名，行为完全一致）
 *   DELETE /api/chat/turn/{turnId}   ← 新增（删除 + cascade）
 *
 * V_NEW_LEAN system prompt 由 server 渲染好成 8 个 XML slot；客户端按 canonical
 * 顺序拼接 [role / character / background / constraints / facts / narrative /
 * client (客户端追加) / tool_protocol] + assistantPrefill。详见
 * docs/client-prompt-merge-protocol.md。
 */

// ── GET /api/character/{id} ──────────────────────────────────────────

data class ChatCharacterResponse(
    val ok: Boolean = false,
    val assistantId: String? = null,
    val profile: ChatCharacterProfile? = null,
    /** decoded camelCase identity row（可空 — 老角色未配 identity 时为 null） */
    val identity: Map<String, Any?>? = null,
    val renderedSlots: ChatRenderedSlots? = null,
    /** etag 形如 "v2.0:identity_v2:profile_1778263928319"; 客户端缓存这个直到失效再重拉 */
    val etag: String? = null,
    val ts: Long = 0,
    val error: String? = null,
)

data class ChatCharacterProfile(
    val characterName: String? = null,
    val characterBackground: String? = null,
    val assistantType: String? = null,
    val allowAutoLife: Boolean = false,
    val allowProactiveMessage: Boolean = false,
)

/** 5 个静态 slot（role / character / background / constraints / tool_protocol）。 */
data class ChatRenderedSlots(
    val role: String? = null,
    val character: String? = null,
    val background: String? = null,
    val constraints: String? = null,
    @SerializedName("tool_protocol") val toolProtocol: String? = null,
)

// ── POST /api/chat/context ───────────────────────────────────────────

data class ChatContextRequest(
    val assistantId: String,
    /** 必传 —— 决定 retrieve query 的 sessionId 上下文 + tool decision 行为 */
    val sessionId: String,
    val userInput: String,
    /** 客户端持有的 slots etag；server 端 etag 一致就回传 renderedSlots=null（客户端用缓存） */
    val haveSlotsETag: String? = null,
    /** 可选 —— 覆盖默认 retrievalTopK */
    val topK: Int? = null,
)

data class ChatContextResponse(
    val ok: Boolean = false,
    val assistantId: String? = null,
    val sessionId: String? = null,
    /** XML <facts>...</facts>（已含 coreFacts + retrieved）；客户端直接塞进 system 段 5 */
    val facts: String? = null,
    /** XML <narrative>...</narrative>（reflection / episodes / topics）；客户端塞进 system 段 6 */
    val narrative: String? = null,
    /** "[此刻]\n..." 内心独白片段；客户端塞到 system 末尾（独立段，不在 system 内） */
    val assistantPrefill: String? = null,
    val salientPhrase: Map<String, Any?>? = null,
    val memoryDecision: ChatMemoryDecision? = null,
    /** 客户端缓存这个 etag；下次 chat/context 调用时回传 haveSlotsETag */
    val etag: String? = null,
    /** server 推断 state 版本；客户端可用作 sync 比对 */
    val stateVersion: Long? = null,
    /**
     * Etag 失配时附带的最新静态 slots。如果为 null 表示客户端缓存仍然有效。
     */
    val renderedSlots: ChatRenderedSlots? = null,
    /** 旧 memory-context 的 memoryLines 兼容（debug 用，可忽略） */
    val memoryLines: List<String>? = null,
    val memoryGuidance: String? = null,
    val ts: Long = 0,
    val error: String? = null,
)

data class ChatMemoryDecision(
    val shouldRetrieve: Boolean = false,
    val intent: String? = null,
    val source: String? = null,
    val reason: String? = null,
    val retrievalError: String? = null,
)

// ── POST /api/chat/turn ──────────────────────────────────────────────
// 行为完全等价 /api/sync/push（语义重命名）；可复用 SyncTurnDto。

data class ChatTurnRequest(
    val deviceId: String? = null,
    /** 顶层 fallback；如果 turns 里每条都有 assistantId/sessionId 可省 */
    val assistantId: String? = null,
    val sessionId: String? = null,
    val turns: List<SyncTurnDto>,
)

data class ChatTurnResponse(
    val ok: Boolean = false,
    val ingested: Int = 0,
    val deduped: Int = 0,
    val rejected: Int = 0,
    val details: List<SyncPushDetail> = emptyList(),
    val error: String? = null,
)

// ── DELETE /api/chat/turn/{turnId} ───────────────────────────────────

data class DeleteTurnResponse(
    val ok: Boolean = false,
    val turnId: String? = null,
    val cascade: DeleteCascade? = null,
    val error: String? = null,
)

data class DeleteCascade(
    val turn: Int = 0,
    val memoryItems: Int = 0,
    val facts: Int = 0,
    val edges: Int = 0,
    val vectors: Int = 0,
    val outboxEvents: Int = 0,
)
