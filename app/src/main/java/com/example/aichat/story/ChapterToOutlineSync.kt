package com.example.aichat.story

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.example.aichat.OutlinePromptBuilder
import com.example.aichat.SessionAssistantBindingStore
import com.example.aichat.SessionChatOptionsStore
import com.example.aichat.SessionOutlineItem
import com.example.aichat.SessionOutlineStore
import com.example.aichat.ChatService
import com.example.aichat.chat.ChatCallback
import com.example.aichat.sync.ToolBridge

/**
 * 「新增章节 + 同步状态」— 从章节内容用 Story Tools 完成两件事：
 *   1. add_outline(type=chapter)：把本章概要写入大纲
 *   2. bump_foreshadow / append_status_history / update_subplot_progress / update_emotion_stage：
 *      更新本章中发生变化的动态条目
 *
 * 替代旧的纯文本 summarizeMessageForOutline 路径，使大纲维护全程走 tool call。
 * 与 BookGenerator / StoryStateSync 一样是 out-of-band 对话，不写入 session 历史。
 */
object ChapterToOutlineSync {

    fun run(
        activity: Activity,
        sessionId: String,
        chapterContent: String,
        executor: java.util.concurrent.Executor,
        runOnUiThread: (Runnable) -> Unit,
    ) {
        if (chapterContent.isBlank()) {
            Toast.makeText(activity, "章节内容为空", Toast.LENGTH_SHORT).show()
            return
        }

        val store = SessionOutlineStore(activity)
        val items = store.getAll(sessionId)

        // 进度对话框
        val ctx = activity
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 24), dp(ctx, 20), dp(ctx, 24), dp(ctx, 12))
        }
        val titleView = TextView(ctx).apply {
            text = "新增章节 + 同步状态…"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
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
        container.addView(titleView)
        container.addView(progress)
        container.addView(log)
        val dialog = AlertDialog.Builder(ctx)
            .setView(container)
            .setCancelable(false)
            .create()
        dialog.show()
        val mainHandler = Handler(Looper.getMainLooper())

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

            val opts = SessionChatOptionsStore(activity).get(sessionId)
            opts.systemPrompt = buildSystemPrompt()

            val toolCalls = mutableListOf<String>()

            val callback = object : ChatCallback {
                override fun onPartial(content: String) {}
                override fun onReasoning(reasoning: String) {}
                override fun onSuccess(content: String) {
                    runOnUiThread(Runnable {
                        dialog.dismiss()
                        val chapterCalls = toolCalls.count { it == StoryToolHandler.TOOL_ADD }
                        val stateCalls = toolCalls.size - chapterCalls
                        AlertDialog.Builder(activity)
                            .setTitle("章节已添加")
                            .setMessage(buildString {
                                if (chapterCalls > 0) append("✓ 已写入章节大纲\n")
                                if (stateCalls > 0) append("✓ 已同步 $stateCalls 项状态/伏笔")
                                else if (chapterCalls > 0) append("（本章无状态变更）")
                                else append("未检测到有效 tool 调用，请检查大纲是否已初始化。")
                            })
                            .setPositiveButton("好") { d, _ -> d.dismiss() }
                            .show()
                    })
                }
                override fun onError(message: String) {
                    runOnUiThread(Runnable {
                        dialog.dismiss()
                        Toast.makeText(activity, "失败: $message", Toast.LENGTH_LONG).show()
                    })
                }
                override fun onCancelled() {
                    runOnUiThread(Runnable { dialog.dismiss() })
                }
                override fun onToolCallStart(toolName: String) {
                    toolCalls.add(toolName)
                    mainHandler.post {
                        log.text = "调用「${toolChineseName(toolName)}」(共 ${toolCalls.size} 次)…"
                    }
                }
            }

            val service = ChatService(activity)
            try {
                service.chat(
                    history = emptyList(),
                    userMessage = buildUserPrompt(chapterContent, items),
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
你是故事编辑助手。用户刚写完一个章节，你需要做两件事：

**第一步：创建章节大纲条目**
- 调用 add_outline(type="chapter", title="第X章 标题", content="不超过300字的章节概要")
- 概要要保留：关键情节转折、出场角色、推进了哪些伏笔/支线/感情线

**第二步：同步动态状态**
- 根据章节内容，用以下 tool 更新已有的动态条目（无变化的保持不动）：
  - bump_foreshadow：推进伏笔状态
  - append_status_history：追加角色/世界状态变化
  - update_subplot_progress：更新支线进度
  - update_emotion_stage：更新感情线阶段

【硬规则】
1. add_outline 只调一次，type 固定为 "chapter"
2. 状态更新只更新，不新建其他类型条目
3. 两步可以并行批量调用，无需串行
4. 完成后用一句话总结（如"已添加第三章，更新了2条伏笔"）
""".trimIndent()

    private fun buildUserPrompt(chapterContent: String, items: List<SessionOutlineItem>): String {
        val sb = StringBuilder()
        sb.append("【本章内容（请据此提取摘要并判断状态变化）】\n")
        sb.append(chapterContent.take(3000))
        if (chapterContent.length > 3000) sb.append("\n…（已截断）")
        sb.append("\n\n")

        val outlineText = OutlinePromptBuilder.build(items, includeKnowledgeEnforcement = false)
        if (outlineText.isNotBlank()) {
            sb.append("【当前大纲状态（已有章节/伏笔/状态/支线/感情线）】\n")
            sb.append(outlineText)
            sb.append("\n")
        } else {
            sb.append("（当前大纲为空，直接创建章节条目即可）\n\n")
        }

        sb.append("请先用 add_outline 添加本章，再批量更新变化的状态条目，无需再调 list_outline。")
        return sb.toString()
    }

    private fun toolChineseName(name: String): String = when (name) {
        StoryToolHandler.TOOL_ADD             -> "新建条目"
        StoryToolHandler.TOOL_UPDATE          -> "更新条目"
        StoryToolHandler.TOOL_BUMP_FORESHADOW -> "推进伏笔状态"
        StoryToolHandler.TOOL_UPDATE_SUBPLOT  -> "更新支线进度"
        StoryToolHandler.TOOL_UPDATE_EMOTION  -> "更新感情线"
        StoryToolHandler.TOOL_APPEND_STATUS   -> "追加状态历史"
        StoryToolHandler.TOOL_LIST            -> "读取大纲列表"
        StoryToolHandler.TOOL_READ            -> "读取条目详情"
        else -> name
    }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
