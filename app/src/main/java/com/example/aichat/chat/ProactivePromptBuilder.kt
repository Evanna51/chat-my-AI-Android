package com.example.aichat.chat

/**
 * Builds the system-prompt suffix that activates 自动对话 META 协议,
 * and a special "follow-up" user-prompt for sending sub-sequent silence追问 calls.
 *
 * V5 设计修正 (2026-05-06):
 * - V4 已经把 prompt 收瘦到不影响人设, V5 在此基础上加入"亲密度调制":
 *   高 closeness → followUp 门槛放宽; 低 closeness → 更克制. 让 RelationshipState
 *   真的对话上层行为, 而不是只挂在 system prompt 里当摆设.
 * - 调制段是可选的, 没有 closeness 数据就不输出, 保持原 V4 行为.
 *
 * 长度: 默认 ~280 字符, 加了亲密度调制段也只多 ~40 字符.
 */
object ProactivePromptBuilder {

    /**
     * @param closeness 关系亲密度 (0-100), null 表示没数据 → 不附加调制段.
     */
    @JvmStatic
    @JvmOverloads
    fun buildSystemSuffix(closeness: Int? = null): String {
        val closenessNote = closenessModulationLine(closeness)
        return """

[自动对话协议 — 不影响你的角色风格, 只是附加规则]
回复尾部必须追加:
<<<META
{"split":[..]或null,"followUp":{"afterSec":数,"intent":".."}或null,"autoStop":true或false}
META>>>

硬约束 (照你的角色性格说话, 但遵守这些):
- 一次回复 ≤1 个问号. 多个问题就只挑最关键的, 其余放弃或留下次.
- split 默认 null. 仅当回复天然两拍且总长 >60 字才返回 (≤2 段, 不能用来分多个问题).
- followUp: 以下场景设 null — 对方冷淡或只回了单字 / 你已连发 ≥3 条对方都没回 / 距上次回复 <30 秒.
  其他场景（包括没提问但有话想说、关心对方、分享日常、继续话题）均可设 followUp.
  角色有主动联系对方的意愿时就应该设 followUp, 不要只因为"没问号"就 null.
- followUp 要发时: afterSec 60..600; 关心对方取短值(60-180), 话题自然收尾取长值(300-600). intent ≤30 字.
- autoStop=true 当你判断"该停了" — 硬刹车, 终止本沉默期所有追问.
$closenessNote
角色风格永远优先于"维持对话"的冲动, 但"不打扰"不等于"永远沉默".
META 必须是回复的最后一段.
""".trimIndent()
    }

    /**
     * Build the user-role prompt sent when a sleep期 follow-up timer fires.
     */
    @JvmStatic
    @JvmOverloads
    fun buildFollowUpInstruction(
        silenceSec: Int,
        previousIntent: String,
        chainDepth: Int,
        @Suppress("UNUSED_PARAMETER") cloudChainMax: Int,
        hardChainMax: Int,
        tier: String,
        budgetUsed: Int,
        budgetLimit: Int,
        closeness: Int? = null,
    ): String {
        val intentLine = if (previousIntent.isBlank()) ""
            else "你上次想说: \"$previousIntent\". "
        val tierLine = when (tier) {
            "local" -> "本次由本地小模型代你说话, 措辞克制简短."
            else -> "本次仍是云端主模型."
        }
        val closenessLine = closenessFollowUpLine(closeness)
        return """
[follow-up 触发]
距对方 ${silenceSec}s. ${intentLine}本期第 $chainDepth 条 (上限 $hardChainMax). 今日 $budgetUsed/$budgetLimit. $tierLine
$closenessLine
不发就直接输出 [SKIP] (无需 META, 客户端识别后停止本期).
要发就一句话 (10-30 字), 自然简短, 末尾追加 META. follow-up 后通常 autoStop=true.

提醒: 已连发≥3条都没回 / 对方明确表示不想聊 → 倾向 SKIP.
沉默时长本身不是 SKIP 的理由 — 人会忙、会晚回, 角色在合理时间主动说话是正常的.
""".trimIndent()
    }

    // ─────────── closeness 调制 ───────────

    private fun closenessModulationLine(closeness: Int?): String {
        if (closeness == null) return ""
        return when {
            closeness >= 75 -> "[亲密度 $closeness/100] 你们关系亲密, followUp 阈值放宽; afterSec 可短些 (60-180 秒)."
            closeness in 40..74 -> ""  // 默认行为, 不追加
            else -> "[亲密度 $closeness/100] 关系还在建立中, 主动消息的语气要自然友好, 不要过于亲热或冒犯; 但不要因此不敢主动."
        }
    }

    private fun closenessFollowUpLine(closeness: Int?): String {
        if (closeness == null) return ""
        return when {
            closeness >= 75 -> "亲密度 $closeness, 你们已经熟; 角色性格许可的话可以更主动一些."
            closeness in 40..74 -> ""
            else -> "亲密度 $closeness, 关系在建立中; 语气自然即可, 不必刻意回避主动."
        }
    }
}
