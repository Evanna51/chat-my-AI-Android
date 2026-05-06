package com.example.aichat.chat

/**
 * Builds the system-prompt suffix that activates 自动对话 META 协议,
 * and a special "follow-up" user-prompt for sending sub-sequent silence追问 calls.
 *
 * V4 设计修正 (2026-05-06):
 * - V3 把规则写得太密太长 (~700 中文字符), 反而稀释了角色原本的人设 system prompt;
 *   模型读完一大段"必须 / 禁止"后, 角色风格就被压住了.
 * - V4 收到 ~280 字符, 只保留硬约束, 把语调调整为"协议附录, 不影响你的人设".
 * - split 准入门槛大幅抬高: 默认 null + 总长 >60 字 + 最多 2 段, 防止"几乎 100% 触发".
 * - [SKIP] 不要求带 META; Worker 端识别即可.
 */
object ProactivePromptBuilder {

    @JvmStatic
    fun buildSystemSuffix(): String = """

[自动对话协议 — 不影响你的角色风格, 只是附加规则]
回复尾部必须追加:
<<<META
{"split":[..]或null,"followUp":{"afterSec":数,"intent":".."}或null,"autoStop":true或false}
META>>>

硬约束 (照你的角色性格说话, 但遵守这些):
- 一次回复 ≤1 个问号. 多个问题就只挑最关键的, 其余放弃或留下次.
- split 默认 null. 仅当回复天然两拍且总长 >60 字才返回 (≤2 段, 不能用来分多个问题).
- followUp 默认 null. 满足任一就 null: 没问问题 / 对方冷淡或单字 / 距上次<1200 秒 / 已连发 ≥2 条没回 / 当前对话已收尾.
- followUp 要发时: afterSec 30..1800; intent ≤30 字.
- autoStop=true 当你判断"该停了" — 硬刹车, 终止本沉默期所有追问.

任何犹豫: 全 null / autoStop 看情况. 角色风格永远优先于"维持对话"的冲动.
META 必须是回复的最后一段.
""".trimIndent()

    /**
     * Build the user-role prompt sent when a sleep期 follow-up timer fires.
     */
    @JvmStatic
    fun buildFollowUpInstruction(
        silenceSec: Int,
        previousIntent: String,
        chainDepth: Int,
        cloudChainMax: Int,
        hardChainMax: Int,
        tier: String,
        budgetUsed: Int,
        budgetLimit: Int,
    ): String {
        val intentLine = if (previousIntent.isBlank()) ""
            else "你上次想说: \"$previousIntent\". "
        val tierLine = when (tier) {
            "local" -> "本次由本地小模型代你说话, 措辞克制简短."
            else -> "本次仍是云端主模型."
        }
        return """
[follow-up 触发]
距对方 ${silenceSec}s. ${intentLine}本期第 $chainDepth 条 (上限 $hardChainMax). 今日 $budgetUsed/$budgetLimit. $tierLine

不发就直接输出 [SKIP] (无需 META, 客户端识别后停止本期).
要发就一句话 (10-30 字), 自然简短, 末尾追加 META. follow-up 后通常 autoStop=true.

提醒: 沉默 600s+ / 连续冷淡 / 没"非说不可"的内容 → 倾向 SKIP.
""".trimIndent()
    }
}
