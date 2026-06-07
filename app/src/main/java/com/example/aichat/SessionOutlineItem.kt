package com.example.aichat

/**
 * Outline 条目 — writer / 故事模式的结构化大纲基础单元。
 *
 * type 取值（语义化命名）:
 *   chapter / volume / world / knowledge — 沿用历史
 *   roles      — 角色档案（原 task，已在 SessionOutlineStore.normalizeType 自动迁移）
 *   foreshadow — 伏笔（原 material，已自动迁移）
 *   status     — 状态卡（current 本地存；history 落 wi-server）
 *   relation   — 角色关系
 *   subplot    — 支线进度
 *   emotion    — 感情线
 *   rules      — 叙事规则（字段化 metaJson，发 prompt 时由 app 拼回 YAML）
 *
 * metaJson 字段按 type 各自定义 schema, 解析见 [com.example.aichat.story.StoryMeta]。
 */
data class SessionOutlineItem(
    @JvmField var id: String = "",
    @JvmField var type: String = "",
    @JvmField var title: String = "",
    @JvmField var content: String = "",
    @JvmField var createdAt: Long = 0L,
    @JvmField var updatedAt: Long = 0L,
    /**
     * 是否参与给主模型的 prompt。默认 true。
     * 给 volume 用：true=用卷纲覆盖区间内的章节大纲；false=不发送卷纲，发送原章节。
     */
    @JvmField var selected: Boolean = true,
    /**
     * 仅 volume 类型使用：该卷纲覆盖的章节标题列表（按 outline 顺序）。
     * selected=true 时这些章节大纲会被 OutlinePromptBuilder 排除。
     */
    @JvmField var volumeChapters: List<String> = emptyList(),
    /**
     * type-specific 结构化字段（JSON 字符串）。
     * 老条目无此字段时反序列化为空串，按 legacy 路径处理。
     * Schema 见 [com.example.aichat.story.StoryMeta]。
     */
    @JvmField var metaJson: String = ""
)
