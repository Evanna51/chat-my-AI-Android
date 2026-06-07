package com.example.aichat.session

import com.example.aichat.Message

/**
 * 作家 / 小说写作模式。
 *
 * 与默认模式的差异：
 * - Adapter 走 writer 文本格式
 * - Toolbar 显示 outline 按钮
 * - 发消息时把会话大纲块拼到用户输入末尾（[buildUserMessageForApi]）
 * - 上传历史时对长助手消息做节选（[buildHistoryForApi]）
 * - 长按消息「转大纲」action 由 [onOutlineAction] 派发回 host
 */
object WriterModeStrategy : SessionModeStrategy {
    override val mode: SessionMode = SessionMode.WRITER
    override val usesCharacterAdapter: Boolean = false
    override val usesWriterAdapter: Boolean = true
    override val disablesAssistantCollapseToggle: Boolean = false
    override val autoFocusLatestOnSetMessages: Boolean = true
    override val hidesPinnedActions: Boolean = false
    override val showsWriterOutlineButton: Boolean = true
    override val showsInkToggle: Boolean = true
    override val supportsAutoTts: Boolean = false
    override val supportsOutlineExport: Boolean = true

    override fun buildUserMessageForApi(rawInput: String, ctx: SessionContext): String {
        val source = rawInput.trim()
        if (source.isEmpty()) return source
        if (ctx.writerOutlineBlock.isEmpty()) return source
        return buildString {
            append(source).append("\n\n")
            append("【写作大纲与资料】\n")
            append(ctx.writerOutlineBlock).append("\n\n")
            append("请严格参考以上内容，保持情节、设定、任务线索的一致性与准确性。")
        }.trim()
    }

    override fun buildHistoryForApi(source: List<Message>, ctx: SessionContext): List<Message> {
        if (source.isEmpty()) return emptyList()
        var lastAssistantIndex = -1
        for (i in source.indices.reversed()) {
            val one = source[i]
            if (one.role == Message.ROLE_ASSISTANT) {
                lastAssistantIndex = i
                break
            }
        }
        val out = ArrayList<Message>(source.size)
        for (i in source.indices) {
            val m = source[i]
            var content = m.content ?: ""
            if (m.role == Message.ROLE_ASSISTANT) {
                content = if (i == lastAssistantIndex) {
                    buildLastAssistantExcerpt(content, ctx.writerLastSegmentChars)
                } else if (content.length > ctx.writerEarlyExcerptMaxChars) {
                    val excerpt = content.substring(0, ctx.writerEarlyExcerptMaxChars)
                    "【节选说明】以下内容为较早助手回复的前${ctx.writerEarlyExcerptMaxChars}字节选，用于保留关键语气与事实锚点；完整情节请以写作大纲与资料为准。\n$excerpt"
                } else {
                    content
                }
            }
            out.add(Message(ctx.sessionId, m.role, content))
        }
        return out
    }

    override fun onOutlineAction(message: Message, host: SessionUiHost): Boolean {
        host.summarizeMessageToOutline(message)
        return true
    }

    /**
     * 最新一条助手回复的「前/中/后段」节选。total ≤ segment*3 时不节选。
     */
    private fun buildLastAssistantExcerpt(content: String, segment: Int): String {
        val total = content.length
        if (total <= segment * 3) return content
        val start = content.substring(0, segment)
        val middleStart = maxOf(0, (total - segment) / 2)
        val middle = content.substring(middleStart, middleStart + segment)
        val end = content.substring(total - segment)
        return "【节选说明】以下内容为最近一条助手回复的分段节选（前${segment}字 / 中间${segment}字 / 后${segment}字），用于保留上下文细节与风格连续性；完整情节请以写作大纲与资料为准。\n" +
                "【前段】\n$start\n【中段】\n$middle\n【后段】\n$end"
    }
}
