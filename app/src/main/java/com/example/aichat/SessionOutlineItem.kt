package com.example.aichat

data class SessionOutlineItem(
    @JvmField var id: String = "",
    @JvmField var type: String = "", // chapter / material / task / world / knowledge / volume
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
    @JvmField var volumeChapters: List<String> = emptyList()
)
