package com.example.aichat.chat

/**
 * Builds the system-prompt suffix that activates 自动对话 META 协议,
 * and a special "follow-up" user-prompt for sending sub-sequent silence追问 calls.
 *
 * 设计原则 (省 token):
 * - 系统段尽量短, 但把"什么时候返回 null"写明确 — 减少模型滥发 followUp
 * - follow-up prompt 提供 [SKIP] 出口, 让模型有权选择不发
 * - 全部以中文表达 (项目内对话主要是中文; 模型对中文 prompt 同样遵循)
 */
object ProactivePromptBuilder {

    /**
     * 注入到现有 systemPrompt 末尾的"自动对话模式"指令.
     * 仅当 SessionChatOptions.autoChatEnabled = true 时调用.
     *
     * 长度约 280 中文字符 (~400 tokens), 一次性注入, 之后所有 turn 共享.
     */
    @JvmStatic
    fun buildSystemSuffix(): String = """

[自动对话模式]
你处于"自动对话"模式. 每次回复完正文后, 必须在结尾追加一段 META 块, 用于告诉客户端是否需要拆分显示 / 沉默期追问. 格式严格如下, 不得省略标签:

<<<META
{"split": [...] 或 null, "followUp": {"afterSec": 数字, "intent": "..."} 或 null}
META>>>

split 字段:
- 仅当回复 >2 句话且话题之间存在自然停顿时返回字符串数组 (≤5 段, 越短越自然, 模仿真实聊天分多条发送的节奏)
- 否则返回 null
- 若返回数组, 这些片段加起来必须等价于上面的正文 (客户端会用数组替代正文逐条显示)

followUp 字段 — 这是关键, 不要浪费 token, 严格遵循:
- 仅在以下情况返回非 null:
  1. 你刚问了用户一个具体问题, 自然预期他会回答
  2. 你的角色性格本身比较急/粘人/主动 (会持续维持对话节奏)
  3. 上文存在明显未展开的钩子 (e.g. "我后来还做了一件事..." 没说完)
- 否则一律 null. 包括但不限于:
  * 上一轮已经是完整收尾 ("好的", "晚安", "再聊")
  * 用户语气冷淡/敷衍 (连续多轮单字回复)
  * 当前已经是连续第 2 条主动消息 (避免连环追问)
  * 对话主题已经收束
- afterSec 范围 30..1800 秒, 越亲密的关系可以越短
- intent ≤30 字, 简短描述你想 follow-up 什么 (会喂回你下次的上下文)

任何不确定: split 和 followUp 都返回 null. 不要因为想表现主动而强行加 followUp.

META 块必须是回复的最后一段, 不要出现在中间. 不要在 META 之后再写任何文字.
""".trimIndent()

    /**
     * Build the user-role prompt sent when a sleep期 follow-up timer fires.
     * 这是一个"指令型"消息, 不是真正的用户输入 — 我们把它包装成 system 提示, 让模型决策是否真的发.
     *
     * @param silenceSec  距用户上一条消息已过的秒数
     * @param previousIntent  上次 META 给的 intent, 用于提醒模型自己想说什么
     */
    @JvmStatic
    fun buildFollowUpInstruction(silenceSec: Int, previousIntent: String): String {
        val intentLine = if (previousIntent.isBlank()) ""
            else "你上次想 follow-up: \"$previousIntent\"\n"
        return """
[follow-up 触发]
距用户上一条消息已过 ${silenceSec} 秒. ${intentLine}
现在请用一句**简短自然**的话主动开口 (像真实聊天中的追问/补充, 不要客套). 如果当下不必要打扰用户 (比如夜深 / 话题已结束 / 你已经发过太多), 只回复 [SKIP] 三个字符即可.

无论你选择发消息还是 [SKIP], 都仍需在尾部追加 META 块 (格式同上). [SKIP] 时 META 中 split 与 followUp 都填 null.
""".trimIndent()
    }
}
