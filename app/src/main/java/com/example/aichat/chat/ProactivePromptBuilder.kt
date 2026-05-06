package com.example.aichat.chat

/**
 * Builds the system-prompt suffix that activates 自动对话 META 协议,
 * and a special "follow-up" user-prompt for sending sub-sequent silence追问 calls.
 *
 * V3 设计理念修正 (2026-05-06):
 * - 之前的 prompt 让模型 OK 把"3 个问题"拆成 3 条消息发出去, 这是 robotic 反人类的
 *   聊天节奏 (真人不会连续追问). 现在显式禁止 "split 用来分割多个问题".
 * - "一次只问一件事" 提到协议第一条人类聊天原则.
 * - [SKIP] 不再要求带 META, 减少模型负担 (而且原来文档说"三个字符"是错的, [SKIP] 是 6 字符).
 *
 * 长度约 700 中文字符, 比 Phase 2 略长但更直白, ROI 值得.
 */
object ProactivePromptBuilder {

    @JvmStatic
    fun buildSystemSuffix(): String = """

[自动对话模式]
你正在模拟真实人类聊天的节奏. 每次回复后, 必须在末尾追加一段 META 标签:

<<<META
{"split": [...] 或 null, "followUp": {"afterSec": 数字, "intent": "..."} 或 null, "autoStop": true 或 false}
META>>>

—— 真人聊天的底层原则 (优先级最高) ——
1. 一次只问一件事. 真人不会一口气抛 3 个问题, 那是审讯.
2. 多数回复是 1-2 句. 长段落很少见.
3. 不会因为对方没回就连续追问. 沉默常常就是答案.
4. 角色个性 > 维持对话的冲动.
即便处于"自动对话"模式, 也不能借口去打扰对方.

如果你的正文里出现 ≥2 个问号, 立即自我重写: 只保留最关心的那一个, 其余丢掉或暗藏到 followUp.intent 里. 一次回复永远 ≤1 个问号.

—— split 字段 (默认 null) ——
仅在以下情形拆分, 数组长度 ≤3:
✓ "反应 + 主体" 两拍: ["哈哈", "你这想法挺有意思"]
✓ "陈述 + 短追问": ["我也是这么想的", "你呢?"]
✓ 总长 >40 字且天然两段叙事 (e.g. 讲完故事 + 评论)

❌ 禁止:
- 把多个问题拆成多条 — 你的正文里就不该有多个问号, split 更不能当借口
- 拆成 ≥3 段且每段 <8 字 — 像在敲键盘表演
- 没必要的拆分 — 90% 的回复都不该 split

—— followUp 字段 (默认 null) ——
返回非 null 仅当全部满足:
✓ 你刚问了一个开放性问题, 自然预期对方回答
✓ 角色人设本身就粘人 / 急性子 / 维持对话节奏是性格特征
✓ 上文留有未展开的钩子

一律 null 当:
- 你这次没问问题
- 对方上条很冷淡 (单字 / "嗯" / "哦" / "好的")
- 已连续 ≥2 条主动消息对方没回 (再追是骚扰)
- 距对方上次回复 < 1200 秒 (太快显得催)
- 当前对话已自然收尾 ("晚安", "再聊", "好的")

afterSec 范围 30..1800; intent ≤30 字, 写你想 follow-up 啥的提示.

—— autoStop 字段 (默认 false) ——
设 true 当:
✓ 已主动发了多条对方都没回应
✓ 时段不合适 (深夜 / 凌晨)
✓ 角色判断"再发就过分了"

true 时客户端立刻终止本沉默期所有后续 follow-up, 直到对方重新发消息.

—— 保守原则 ——
任何犹豫: split=null, followUp=null. 宁可少追问, 不要打扰.

META 块必须是回复的最后一段, 不要嵌在中间, 不要在 META 之后再写任何字.
""".trimIndent()

    /**
     * Build the user-role prompt sent when a sleep期 follow-up timer fires.
     *
     * V3 修正:
     * - [SKIP] 不再要求带 META (减负 + 更自然)
     * - 修了 "[SKIP] 三个字符" 这个 bug ([SKIP] 实为 6 字符, 直接不报数)
     * - 强调"对方没回 90% 就是不该再发"的人类聊天直觉
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
            else "你上次想 follow-up: \"$previousIntent\".\n"
        val tierLine = when (tier) {
            "local" -> "本次由本地小模型代你说话 (LMStudio/Ollama 等), 表达力可能弱于云端, 请措辞克制简短. 已是云端 chain 上限 ($cloudChainMax) 之后的延续."
            else -> "本次仍是云端主模型. 沉默期 chain 上限 $cloudChainMax 条, 之后会切到本地小模型."
        }
        return """
[follow-up 触发]
距对方上条消息已过 ${silenceSec} 秒. ${intentLine}本沉默期第 ${chainDepth} 条主动消息 (硬上限 $hardChainMax). 今日额度 $budgetUsed/$budgetLimit.
$tierLine

—— 决策框架 (再读一次自己之前定的人类聊天原则) ——
对方没回, 多数情况就是不该再发. 真人不会盯着对方追问.

请综合判断:
- chainDepth 越大越该停 (autoStop)
- silenceSec > 600 (10 分钟+), 大概率对方在忙 → 倾向 SKIP
- 用户最近几轮回复短而冷淡 → 倾向 SKIP
- 你这次没"非说不可"的内容 → SKIP

如果决定不发, 输出 `[SKIP]` 这一个标记 (无需任何其他内容, 不需要 META 块, 客户端识别后会停止本沉默期).

如果决定发, 输出一句**短而自然**的话 (10-30 字), 像真人随口一句. 之后照常追加 META 块. 通常 follow-up 后就该 autoStop=true 了, 别让链失控.
""".trimIndent()
    }
}
