package com.example.aichat.story

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
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
 * 「同步本章状态」— 读取最近一次 AI 输出的章节内容，用 Story Tools 更新：
 *   伏笔状态 (bump_foreshadow) / 状态卡 (append_status_history)
 *   支线进度 (update_subplot_progress) / 感情线 (update_emotion_stage)
 *
 * 不新增条目，不修改章节大纲本身 — 只做"读章节、改状态"的收尾动作。
 * 与 BookGenerator 一样是 out-of-band 对话，不写入 session 历史。
 */
object StoryStateSync {

    fun run(activity: Activity, sessionId: String, executor: Executor, runOnUiThread: (Runnable) -> Unit) {
        val store = SessionOutlineStore(activity)
        val items = store.getAll(sessionId)

        // 取最新一条 assistant 消息作为"本章内容"
        val lastChapter = lastAssistantMessage(activity, sessionId)
        if (lastChapter.isBlank()) {
            Toast.makeText(activity, "没有找到最近的章节内容", Toast.LENGTH_SHORT).show()
            return
        }

        // 有没有可以更新的条目
        val syncTargets = items.filter { it.type in listOf(
            StoryTypes.FORESHADOW, StoryTypes.STATUS, StoryTypes.SUBPLOT, StoryTypes.EMOTION
        )}
        if (syncTargets.isEmpty()) {
            Toast.makeText(activity, "大纲中没有伏笔/状态/支线/感情线条目", Toast.LENGTH_SHORT).show()
            return
        }

        // 进度对话框
        val ctx = activity
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(ctx, 24), dp(ctx, 20), dp(ctx, 24), dp(ctx, 12))
        }
        val title = TextView(ctx).apply {
            text = "同步本章状态…"
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
            val userPrompt = buildUserPrompt(lastChapter, items)

            val opts = SessionChatOptionsStore(activity).get(sessionId)
            opts.systemPrompt = systemPrompt

            val toolCalls = mutableListOf<String>()

            val callback = object : ChatCallback {
                override fun onPartial(content: String) {}
                override fun onReasoning(reasoning: String) {}
                override fun onSuccess(content: String) {
                    runOnUiThread(Runnable {
                        dialog.dismiss()
                        AlertDialog.Builder(activity)
                            .setTitle("同步完成")
                            .setMessage(buildString {
                                if (toolCalls.isEmpty()) {
                                    append("本章未检测到需要更新的状态/伏笔。")
                                } else {
                                    append("已更新 ${toolCalls.size} 项：\n")
                                    toolCalls.take(15).forEachIndexed { i, t ->
                                        append("  ${i + 1}. ").append(toolChineseName(t)).append("\n")
                                    }
                                    if (toolCalls.size > 15) append("  …还有 ${toolCalls.size - 15} 次")
                                }
                            })
                            .setPositiveButton("好") { d, _ -> d.dismiss() }
                            .show()
                    })
                }
                override fun onError(message: String) {
                    runOnUiThread(Runnable {
                        dialog.dismiss()
                        Toast.makeText(activity, "同步失败: $message", Toast.LENGTH_LONG).show()
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
你是故事编辑助手。用户刚写完一个章节，你需要根据章节内容，用 story tools 更新书中已有的动态条目。

【硬规则】
1. **只更新**，不新建任何条目。
2. 只处理：伏笔 (bump_foreshadow)、状态卡 (append_status_history)、支线进度 (update_subplot_progress)、感情线 (update_emotion_stage)。
3. 只更新本章中确实发生了变化的条目；无变化的保持不动。
4. 可以同一轮批量调用多个 tool，不需要串行。
5. 完成后用一句话总结更新了哪些内容（如"更新了 2 条伏笔状态、1 条感情线"）。
""".trimIndent()

    private fun buildUserPrompt(
        lastChapter: String,
        items: List<com.example.aichat.SessionOutlineItem>,
    ): String {
        val sb = StringBuilder()
        sb.append("【本章内容（最新 AI 输出，请据此判断状态变化）】\n")
        sb.append(lastChapter.take(3000))
        if (lastChapter.length > 3000) sb.append("\n…（已截断）")
        sb.append("\n\n")

        // 注入可更新的条目现状（不需要模型再 list_outline）
        val outlineText = OutlinePromptBuilder.build(items, includeKnowledgeEnforcement = false)
        if (outlineText.isNotBlank()) {
            sb.append("【当前大纲状态（只关注伏笔/状态卡/支线/感情线部分）】\n")
            sb.append(outlineText)
            sb.append("\n")
        }

        sb.append("请根据本章内容，批量更新上述条目中发生了变化的部分，无需调 list_outline。")
        return sb.toString()
    }

    private fun lastAssistantMessage(activity: Activity, sessionId: String): String {
        return try {
            val messages = AppDatabase.getInstance(activity).messageDao().getBySession(sessionId)
            messages.lastOrNull { it.role == Message.ROLE_ASSISTANT && !it.content.isNullOrBlank() }
                ?.content?.trim().orEmpty()
        } catch (_: Exception) { "" }
    }

    private fun toolChineseName(name: String): String = when (name) {
        StoryToolHandler.TOOL_LIST            -> "读取大纲列表"
        StoryToolHandler.TOOL_READ            -> "读取条目详情"
        StoryToolHandler.TOOL_ADD             -> "新建条目"
        StoryToolHandler.TOOL_UPDATE          -> "更新条目"
        StoryToolHandler.TOOL_DELETE          -> "删除条目"
        StoryToolHandler.TOOL_RENAME_ROLE     -> "重命名角色"
        StoryToolHandler.TOOL_BUMP_FORESHADOW -> "推进伏笔状态"
        StoryToolHandler.TOOL_UPDATE_SUBPLOT  -> "更新支线进度"
        StoryToolHandler.TOOL_UPDATE_EMOTION  -> "更新感情线"
        StoryToolHandler.TOOL_APPEND_STATUS   -> "追加状态历史"
        StoryToolHandler.TOOL_PATCH_CHAPTER   -> "修改章节文本"
        else -> name
    }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
