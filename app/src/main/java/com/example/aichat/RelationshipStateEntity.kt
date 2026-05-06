package com.example.aichat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cold-cache for the assistant ↔ user relationship state served by wi-chat-server.
 *
 * 设计原则:
 * - 主键是 assistantId — 一个 assistant 与当前用户之间只维护一行;
 *   多用户场景 (未来 SaaS) 出现时再扩 userId 复合主键, 当前用户只有自己。
 * - 字段保持「server 真相 + 本地最近一次拉取时间戳」结构。在线时调
 *   `/api/relationship/state` 拿最新, 离线/失败时回退到这张表。
 * - 列表型字段 (sharedTopics, sourceMemoryIds 等) 直接存 JSON 字符串, 反正
 *   不参与查询, 也不需要 join。
 * - rawJson 兜底任何 server schema 的临时新增, 不必每次加字段都跟 migration。
 */
@Entity(tableName = "relationship_state")
class RelationshipStateEntity {

    @PrimaryKey
    @JvmField
    var assistantId: String = ""

    /** 0-100 亲密度, server 算; 拉不到默认 0. */
    @ColumnInfo(defaultValue = "0")
    @JvmField
    var closeness: Int = 0

    /** server 给的信任标签 (e.g. "warm" / "guarded" / "developing"); 字符串避免枚举绑死. */
    @ColumnInfo(defaultValue = "''")
    @JvmField
    var trustLevel: String = ""

    /** JSON array string: ["健身", "项目复盘", ...]; UI/prompt 注入时反序列化. */
    @ColumnInfo(defaultValue = "'[]'")
    @JvmField
    var sharedTopicsJson: String = "[]"

    /** server 给的最近情绪基调 (e.g. "calm" / "tense" / "playful"). */
    @ColumnInfo(defaultValue = "''")
    @JvmField
    var lastEmotionalTone: String = ""

    /** 用户最后一次跟 assistant 互动的时间戳 (server 时区, ms). 0 = 未知. */
    @ColumnInfo(defaultValue = "0")
    @JvmField
    var lastInteractionAt: Long = 0

    /** 客户端最后一次成功从 server 拉到这条记录的时间戳 (本地 ms). */
    @ColumnInfo(defaultValue = "0")
    @JvmField
    var fetchedAt: Long = 0

    /**
     * server 原始 payload 兜底。后续 server schema 加字段时不必每次 migration —
     * 业务层从 rawJson 里读, 字段稳定下来后再升级到 typed column.
     */
    @ColumnInfo(defaultValue = "''")
    @JvmField
    var rawJson: String = ""
}
