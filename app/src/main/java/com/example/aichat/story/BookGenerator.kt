package com.example.aichat.story

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.example.aichat.AppDatabase
import com.example.aichat.ChatService
import com.example.aichat.Message
import com.example.aichat.OutlinePromptBuilder
import com.example.aichat.SessionAssistantBindingStore
import com.example.aichat.SessionChatOptionsStore
import com.example.aichat.SessionOutlineStore
import com.example.aichat.chat.ChatCallback
import com.example.aichat.sync.ToolBridge
import java.util.concurrent.Executor

/**
 * 「生成书籍」入口 — 用 Story Tools 反向初始化 outline。
 *
 * 流程:
 *  1. 校验 ≥1 章 chapter (否则 Toast 退出)
 *  2. 构造 system + user prompt: 指令明确"基于章节内容补全 world / roles / volume_map / rules,
 *     必须通过 story tools 写入, 不要输出纯文本"
 *  3. 调 ChatService.chat (流式 + ToolBridge 强制 story-tools 启用)
 *  4. 监听 onToolCallStart / onSuccess 回调更新进度对话框
 *  5. 完成后展示新增条目数, 用户可去 outline 页查看
 *
 * 这是一个一次性的 "out-of-band" 对话, 不写入 session 历史 — 模型回复也不持久化。
 * 所有结果都通过 tool call 写到 SessionOutlineStore, 自然落地。
 */
object BookGenerator {

    fun run(activity: Activity, sessionId: String, executor: Executor, runOnUiThread: (Runnable) -> Unit) {
        val store = SessionOutlineStore(activity)
        val items = store.getAll(sessionId)
        val chapters = items.filter { it.type == StoryTypes.CHAPTER }
        if (chapters.isEmpty()) {
            Toast.makeText(activity, "请先添加至少一章章节大纲", Toast.LENGTH_SHORT).show()
            return
        }

        // 进度对话框
        val ctx = activity
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(ctx, 24), dp(ctx, 20), dp(ctx, 24), dp(ctx, 12))
        }
        val title = TextView(ctx).apply {
            text = "生成书籍中…"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 12))
        }
        val log = TextView(ctx).apply {
            textSize = 12f
            text = "准备 prompt…"
            setTextColor(ctx.getColor(com.example.aichat.R.color.ios_section_label))
        }
        container.addView(title)
        container.addView(progress)
        container.addView(log)
        val dialog = AlertDialog.Builder(ctx)
            .setView(container)
            .setCancelable(false)
            .create()
        dialog.show()
        val mainHandler = Handler(Looper.getMainLooper())

        // 后台 thread 构造 + 发请求
        executor.execute {
            val assistantId = SessionAssistantBindingStore(activity).getAssistantId(sessionId)
            val bridge = ToolBridge.build(activity, assistantId, sessionId)
            if (bridge == null) {
                runOnUiThread(Runnable {
                    dialog.dismiss()
                    Toast.makeText(activity, "无法初始化工具桥 (检查 assistant/远程配置)", Toast.LENGTH_LONG).show()
                })
                return@execute
            }

            val systemPrompt = buildSystemPrompt()
            val userPrompt = buildUserPrompt(items)

            val opts = SessionChatOptionsStore(activity).get(sessionId)
            opts.systemPrompt = systemPrompt

            val callsBefore = items.size
            val toolCalls = mutableListOf<String>()
            var lastError: String? = null

            val callback = object : ChatCallback {
                override fun onPartial(content: String) { /* ignore */ }
                override fun onReasoning(reasoning: String) {}
                override fun onSuccess(content: String) {
                    runOnUiThread(Runnable {
                        dialog.dismiss()
                        val callsAfter = store.getAll(sessionId).size
                        val added = (callsAfter - callsBefore).coerceAtLeast(0)
                        AlertDialog.Builder(activity)
                            .setTitle("生成完成")
                            .setMessage(buildString {
                                append("新增条目: ").append(added).append("\n")
                                append("工具调用: ").append(toolCalls.size).append(" 次\n\n")
                                if (toolCalls.isNotEmpty()) {
                                    append("调用记录:\n")
                                    toolCalls.take(20).forEachIndexed { i, t ->
                                        append("  ${i + 1}. ").append(t).append("\n")
                                    }
                                    if (toolCalls.size > 20) append("  …还有 ${toolCalls.size - 20} 次")
                                }
                            })
                            .setPositiveButton("好") { d, _ -> d.dismiss() }
                            .show()
                    })
                }
                override fun onError(message: String) {
                    lastError = message
                    runOnUiThread(Runnable {
                        dialog.dismiss()
                        Toast.makeText(activity, "生成失败: $message", Toast.LENGTH_LONG).show()
                    })
                }
                override fun onCancelled() {
                    runOnUiThread(Runnable { dialog.dismiss() })
                }
                override fun onToolCallStart(toolName: String) {
                    toolCalls.add(toolName)
                    mainHandler.post {
                        log.text = "调用 $toolName (${toolCalls.size} 次)…"
                    }
                }
            }

            // 直接调 ChatService — 不写入 session 历史, 不挂在 ViewModel
            val service = ChatService(activity)
            try {
                service.chat(
                    history = emptyList(),
                    userMessage = userPrompt,
                    options = opts,
                    callback = callback,
                    toolBridge = bridge,
                )
            } catch (t: Throwable) {
                runOnUiThread(Runnable {
                    dialog.dismiss()
                    Toast.makeText(activity, "调用失败: ${t.message}", Toast.LENGTH_LONG).show()
                })
            }
        }
    }

    // ─────────────── prompt builders ───────────────

    private fun buildSystemPrompt(): String = """
你是一位资深的小说编辑与故事策划。用户已经为一本小说写好了若干章节大纲, 现在需要你**反向初始化**这本书的设定层 —— world / roles / volume / rules / 关键 foreshadow。

【硬规则】
1. 你**必须**通过 story tools 把结果落库; 直接输出文字会被丢弃。
2. 一次只调一个 tool, 等返回后再调下一个。
3. 角色 (roles): 至少 1 个主角 (tier=major) + 数个重要配角 (minor); metaJson 字段尽量填齐 (tier/tags/personality/background/motivation/arc)。
4. 卷 (volume): 若 chapter 数 > 5, 适度划卷; 每卷 volumeChapters 列出覆盖的章节标题。
5. 世界观 (world) + 知情约束 (knowledge): 从章节场景与角色信息提取; 各 1-3 条即可, 别堆砌。
6. 叙事规则 (rules): 仅在 session 没有 rules 条目时新建; metaJson 字段 protagonist/tone/pov/tense 必填, taboos/styleRefs 可选。
7. 关键伏笔 (foreshadow): 识别 2-5 个跨章节的核心伏笔, state 按当前章节进度判定 (planted/developing)。

【建议工作流】
- 先 list_outline 看现状, 再决定要补什么
- 不要重复创建已有名字的角色 (调 list_outline type=roles 检查)
- 完成后用一句话向用户确认"已为 [书名] 初始化 N 个角色、M 个卷、L 条伏笔"
""".trimIndent()

    private fun buildUserPrompt(items: List<com.example.aichat.SessionOutlineItem>): String {
        val chapters = items.filter { it.type == StoryTypes.CHAPTER }
        val sb = StringBuilder()
        sb.append("以下是用户已经写好的章节大纲, 请用 story tools 反向初始化书的设定层:\n\n")
        for ((i, c) in chapters.withIndex()) {
            sb.append(i + 1).append(". ").append(c.title.trim().ifEmpty { "(无标题)" }).append("\n")
            if (c.content.isNotBlank()) {
                sb.append(c.content.trim().take(600)).append("\n\n")
            }
        }
        // 已有内容也告诉模型, 避免重复
        val existingByType = items.groupBy { it.type }
        sb.append("\n【已有 outline 状态】\n")
        for ((type, list) in existingByType) {
            if (type == StoryTypes.CHAPTER) continue
            sb.append("- ").append(type).append(": ").append(list.size).append(" 条\n")
        }
        sb.append("\n请先 list_outline 看清现状, 然后按上面规则补全。")
        return sb.toString()
    }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
