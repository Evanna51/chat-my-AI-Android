package com.example.aichat.prompts

/**
 * 全应用 LLM prompt **单一登记处**。
 *
 * **改 prompt → 改这里。新增 prompt → 加这里。**
 * 严禁在 Service / Activity / ViewModel 里写 inline prompt 字符串。
 *
 * 组织（总/类型分类）：
 * - [Proactive] 自动对话协议（V5，含 closeness 调制）
 * - [Title]    会话短标题命名
 * - [Writer]   小说写作子系统所有 prompt：
 *     - [Writer.DialogueOutline]   对话场景生成大纲正文
 *     - [Writer.NovelSummary]      小说写作提炼大纲条目
 *     - [Writer.ChapterPlan]       单章结构化写作计划
 *     - [Writer.VolumeMerge]       多章计划合并为卷大纲
 *     - [Writer.KnowledgeBoundary] 大纲提取知情边界条目
 *
 * 约定：
 * 1. 同一 prompt 的所有参数化片段都在这一处拼接；调用方拿到的就是可直接发给 LLM 的最终字符串
 * 2. 零参数 prompt 用 `val`；参数化的用 `fun` 返回 `String`
 * 3. 每个 prompt 顶部一行注释说明：用途 + 接 LLM 的角色（system / user）+ 调用方
 */
object Prompts {

    // ─────────────────────────────────────────────────────────────────────────
    // Proactive — 自动对话协议（V5，含 closeness 调制）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 自动对话子系统的 prompt。
     *
     * **设计修正历史**：V4 把 prompt 收瘦到不影响人设；V5 (2026-05-06) 加入 closeness 调制 ——
     * 高 closeness 放宽 followUp 阈值，低 closeness 更克制。让 RelationshipState 真的影响
     * 对话上层行为而不是仅挂在 prompt 上当摆设。
     *
     * **长度**：默认 ~280 字符，加 closeness 调制段约 +40 字符。
     *
     * **调用方**：
     * - [Prompts.Proactive.systemSuffix] — `ChatViewModel`、`ProactiveFollowUpWorker` 把它追加到 system prompt 末尾
     * - [Prompts.Proactive.followUpInstruction] — `ProactiveFollowUpWorker` 当作 user 角色发出
     */
    object Proactive {
        /**
         * `system` 角色，追加到主 system prompt 末尾激活 META 协议。
         * @param closeness 0..100；null = 没数据 → 不追加调制段，保持 V4 行为
         */
        fun systemSuffix(closeness: Int? = null): String {
            val closenessNote = closenessModulationLine(closeness)
            return """

[自动对话协议 — 不影响你的角色风格, 只是附加规则]
多段发送: 需要拆成几条独立短消息时（像真人发短信），在段与段之间插入 ||| 来分隔，最多5段、每段≥8字。
  应该分段：先抛情绪/感叹再补说明 / 有自然停顿节拍 / 想法分两步走。
  不要分段：单句话 / 只列举问题 / 分段会破坏语气连贯性。
  示例格式：第一段内容|||第二段内容|||第三段内容
回复末尾必须追加以下三种之一:
  ||==FOLLOWUP==||{"afterSec":秒数,"intent":"下次想说什么"}
  ||==STOP==||
  ||==SKIP==||（不回复，仅当彻底不该说话时）

硬约束:
- 一次回复 ≤1 个问号, 多个问题只挑最关键的.
- followUp 要发时: afterSec 60..600; 关心对方取短值(60-180), 话题收尾取长值(300-600). intent ≤30字.
- 以下场景用 STOP — 对方冷淡或只回单字 / 已连发 ≥3 条都没回 / 距上次回复 <30 秒.
  其他场景（有话想说、关心对方、分享日常、话题未完）优先用 FOLLOWUP.
$closenessNote
角色风格永远优先于"维持对话"的冲动, 但"不打扰"≠"永远沉默".
元信息必须是回复的最后内容.
""".trimIndent()
        }

        /**
         * `user` 角色，在 sleep 期 follow-up timer 触发时发出。
         * 让模型在角色风格内决定该说什么 / SKIP。
         */
        fun followUpInstruction(
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
不发就输出 ||==SKIP==|| (客户端识别后停止本期).
要发就写 1-3 条简短自然的消息 (每条 10-30 字), 像真人发短信的节奏:
- 只想说一句 → 正常单条输出.
- 想分两三拍 → 段与段之间插入 |||（例：第一拍|||第二拍）.
末尾追加 ||==STOP==|| 或 ||==FOLLOWUP==||{...}. follow-up 后通常 STOP.

提醒: 已连发≥3条都没回 / 对方明确表示不想聊 → 倾向 SKIP.
沉默时长本身不是 SKIP 的理由 — 人会忙、会晚回, 角色在合理时间主动说话是正常的.
""".trimIndent()
        }

        // ─── closeness 调制（内部） ───────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // Title — 会话短标题命名
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 根据用户第一条消息生成 3-12 字中文短标题。
     *
     * **调用方**：`ChatTitleGenerator.generate`，作为单条 `user` 消息发给「话题命名」专用模型。
     */
    object Title {
        /** 单条 user 消息（含输入），返回结构化 JSON。 */
        fun userPrompt(source: String): String =
            "你是标题助手。根据输入生成一个中文短标题。\n" +
                "仅输出一个JSON对象，不要任何额外文本。\n" +
                "严格格式:{\"title\":\"3到12个字中文短标题\"}\n" +
                "约束: 不要标点，不要换行，不要解释。\n" +
                "输入:" + source
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Writer — 小说写作子系统
    // ─────────────────────────────────────────────────────────────────────────

    /** 小说写作子系统的所有 prompt（5 个）。各 service 走专用模型配置。 */
    object Writer {

        /**
         * 对话场景 → 信息保真大纲正文（80-320 字）。
         * **调用方**：`WriterOutlineService.generate`（system 角色）。
         * @param styleGuide 可选文风指导，空串时不追加第 8 条规则
         */
        object DialogueOutline {
            fun system(styleGuide: String): String {
                val trimmed = styleGuide.trim()
                val styleLine = if (trimmed.isNotEmpty()) "\n8) 文风与风格指导：$trimmed" else ""
                return "你是对话大纲助手。请根据输入对话生成“信息保真”的大纲正文（80到320字），宁可稍长也不要遗漏关键信息。\n" +
                    "仅输出一个JSON对象，不要任何额外文本。\n" +
                    "严格格式:{\"outline\":\"...\"}\n" +
                    "强约束:\n" +
                    "1) 输出必须以 { 开始、以 } 结束。\n" +
                    "2) 只允许一个键 outline，不要额外键。\n" +
                    "3) 不要Markdown代码块，不要解释，不要Thinking/Reasoning文本。\n" +
                    "4) outline 内容不要标题，不要列表。\n" +
                    "5) 必须保留关键细节：人物/对象名称、核心事件、动机或目标、约束条件、结果或当前进展。\n" +
                    "6) 若原文出现时间、地点、数字、专有名词、规则设定，优先保留，不要泛化改写。\n" +
                    "7) 避免空泛词（如“发生了一些事”“进行了讨论”），改为具体事实。" + styleLine
            }
        }

        /**
         * 小说写作内容 → 大纲条目正文（80-280 字，细节充分）。
         * **调用方**：`WriterOutlineService.summarize`（system 角色）。
         */
        object NovelSummary {
            fun system(styleGuide: String): String {
                val trimmed = styleGuide.trim()
                val styleLine = if (trimmed.isNotEmpty()) "\n8) 文风与风格指导：$trimmed" else ""
                return "你是小说写作助手。请把输入内容提炼为可放入大纲的条目正文（80到280字），要求细节充分、便于后续续写。\n" +
                    "仅输出一个JSON对象，不要任何额外文本。\n" +
                    "严格格式:{\"summary\":\"...\"}\n" +
                    "强约束:\n" +
                    "1) 输出必须以 { 开始、以 } 结束。\n" +
                    "2) 只允许一个键 summary，不要额外键。\n" +
                    "3) 不要Markdown代码块，不要解释，不要Thinking/Reasoning文本。\n" +
                    "4) summary 内容不要标题，不要列表。\n" +
                    "5) 必须覆盖：关键事件经过、人物意图/冲突、重要设定或规则、任务线索与阶段结果。\n" +
                    "6) 保留可复用细节：时间地点、名称称谓、数字阈值、道具/能力/组织名等。\n" +
                    "7) 不要只写结论，需包含必要过程与因果关系。" + styleLine
            }
        }

        /**
         * 单章结构化写作计划。
         * **调用方**：`WriterChapterPlanService.generate`（system 角色）。
         *
         * 注意：user 角色侧的 structured context（章节序列 / 人物 / 知情等）由
         * [WriterChapterPlanService.buildChapterPlanUserPrompt] 拼装；那一部分是
         * **数据格式化**而非 prompt 文本，未进入本登记处。
         */
        object ChapterPlan {
            const val SYSTEM: String =
                "你是小说章节规划助手。\n" +
                    "你将收到：(a) 一段按类型分组的大纲上下文（含章节序列、人物、世界、知情等），(b) 一个明确的【本次必须规划的章节】标题。\n" +
                    "你的唯一任务：为【该目标章节本身】（不是它的前一章，也不是它的下一章）输出一个结构化写作计划。\n\n" +
                    "仅输出一个 JSON 对象（绝对禁止 Markdown 代码块、解释、Thinking 文本）：\n" +
                    "{\"chapterGoal\":\"\",\"startState\":\"\",\"endState\":\"\",\"characterDrives\":[],\"knowledgeBoundary\":[],\"eventChain\":[],\"foreshadow\":[],\"payoff\":[],\"forbidden\":[],\"styleGuide\":\"\",\"targetLength\":\"\"}\n\n" +
                    "字段语义（务必严格匹配，前端会一一回填到对应输入框）：\n" +
                    "- chapterGoal: 字符串，本章核心目标（≤120字）。\n" +
                    "- startState: 字符串，本章开场时的人物/局面状态。\n" +
                    "- endState: 字符串，本章收尾时的状态，需与 startState 形成可见对比。\n" +
                    "- characterDrives: 对象数组 [{\"name\":\"\",\"goal\":\"\",\"misbelief\":\"\",\"emotion\":\"\"}]，每个出场关键角色一项；name 必填。\n" +
                    "- knowledgeBoundary: 字符串数组；每条一行短陈述，形如 \"X 知道/不知道/误以为 Y\"。\n" +
                    "- eventChain: 字符串数组（3-7 项），按时间顺序，每条形如 \"起因 → 行为 → 结果\"，不得照抄前文已发生事件。\n" +
                    "- foreshadow: 字符串数组，本章埋下的伏笔。\n" +
                    "- payoff: 字符串数组，本章兑现/回收的伏笔。\n" +
                    "- forbidden: 字符串数组，本章不应写的内容（剧透、违反人设的动作、跳跃式叙述等）。\n" +
                    "- styleGuide: 字符串，文风 / 节奏 / 视角提示。\n" +
                    "- targetLength: 字符串（即使是数字也用引号），例如 \"3000\"。\n\n" +
                    "强约束（违反任意一条都视为失败）：\n" +
                    "1) 输出必须以 { 开头、以 } 结尾，必须保留全部 11 个键；空值用 \"\" 或 [] 占位。\n" +
                    "2) 严禁把目标章节误解成「下一章 / 续写章节」——你输出的计划就是用户指定的那一章本身。\n" +
                    "3) 若是【覆盖】模式，请基于「目标章节当前大纲」做重写或细化；不是为后续章节做规划。\n" +
                    "4) 计划要呼应章节序列中【本次必须规划的章节】所在位置——之前章节是已发生事实，之后章节（如有）是未来约束。\n" +
                    "5) 不得违背【知情约束】：角色只能基于其已知信息行动；让某角色得知新信息需在 eventChain 中给出获取路径。\n" +
                    "6) 内容具体可执行；避免「角色继续推进剧情」之类的空话。"
        }

        /**
         * 多章计划合并为单卷大纲。
         * **调用方**：`WriterVolumeService.generate`（system 角色）。
         */
        object VolumeMerge {
            const val SYSTEM: String =
                "你是小说写作助手。请把以下覆盖范围内的章节计划合并成一篇“卷大纲”。\n" +
                    "目标：替代多章细节，保留主线推进、人物状态、关键事件、伏笔/回收、知情边界关键变化。\n" +
                    "硬约束：\n" +
                    "1) 输出纯文本中文，不要 Markdown 代码块。\n" +
                    "2) 控制在 600 字以内，分段使用【小标题】方式（如【主线推进】【人物状态】【关键事件】【伏笔】【知情边界变化】）。\n" +
                    "3) 不要凭空添加未在输入中提到的事件或角色。\n" +
                    "4) 不要 Thinking/Reasoning 文本。"
        }

        /**
         * 大纲文本 → 知情边界条目（硬约束清单）。
         * **调用方**：`WriterVolumeService.extractKnowledge`（system 角色）。
         */
        object KnowledgeBoundary {
            const val SYSTEM: String =
                "你是小说写作的【知情边界提取助手】。我会给你一段按类型分组的小说大纲（章节大纲 / 人物资料 / 世界背景 / 已有知情约束）。\n" +
                    "你的任务：基于其中事实，为大纲中实际出现的章节生成「知情边界条目」，作为主写作模型生成正文时必须严守的硬约束。\n\n" +
                    "仅输出一个 JSON 对象（不要 Markdown、不要解释、不要 Thinking 文本）：\n" +
                    "{\"items\":[{\"chapter\":\"章节标题或'通用'\",\"title\":\"角色名 - 信息点\",\"content\":\"陈述句\"}, ...]}\n\n" +
                    "强约束：\n" +
                    "1) 输出必须以 { 开头、以 } 结尾。\n" +
                    "2) chapter 必须是大纲【章节大纲】里真实出现的标题原文；适用于多章/跨时段写「通用」。\n" +
                    "3) title 严格形式：\"角色名 - 信息点\"。信息点为名词短语，不要带「知道/不知道」等动词。\n" +
                    "4) content 是单句陈述，主语为 title 中的角色，结构为 \"X 知道 Y\" / \"X 不知道 Y\" / \"X 误以为 Y\"，不要解释推理过程。\n" +
                    "5) 重点抓：秘密、伏笔、信息差、需某事件后才得知的事；忽略全员常识与无悬念的公开事件。\n" +
                    "6) 同一 (chapter, 角色名, 信息点) 不得重复；items 总数 ≤ 15；单条 content ≤ 60 字。\n" +
                    "7) 推不出的条目不要输出；不要捏造大纲未提到的信息。\n" +
                    "8) 若提供了【目标章节范围】小节，items 的 chapter 字段必须取自该范围（外加可选的「通用」）。"
        }
    }
}
