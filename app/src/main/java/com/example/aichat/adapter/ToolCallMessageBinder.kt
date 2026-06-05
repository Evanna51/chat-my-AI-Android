package com.example.aichat.adapter

import com.example.aichat.Message
import com.google.gson.JsonParser

/**
 * 把连续的 `role=ROLE_TOOL_CALL` / `role=ROLE_TOOL_RESULT` 消息行格式化成
 * 一段简短的 reasoning-style 摘要，显示在助手气泡的 reasoning 折叠区里。
 *
 * 从 MessageAdapter 抽出（R10）。纯函数，无状态，无副作用 —— 实现成 `object`，
 * MessageAdapter 调用方按需 `ToolCallMessageBinder.formatBuffer(...)`。
 *
 * 协议约定：
 * - ROLE_TOOL_CALL 行：通过 [parseFirstToolCall] 解出 `function.name`/`arguments`
 *   显示成 `🔧 调用 <name>(<args 前 120 字>...)`
 * - ROLE_TOOL_RESULT 行：显示 `→ <toolName> 返回:\n<content 前 400 字>`，
 *   超长截断标记 `…(已截断, 完整结果见工具调用日志)`
 *
 * 摘要拼好后由 MessageAdapter 塞进 `holder.textReasoning.text`，跟模型自身
 * 输出的 reasoning 一样走折叠展开逻辑。
 */
object ToolCallMessageBinder {

    private const val ARGS_PREVIEW_MAX = 120
    private const val RESULT_PREVIEW_MAX = 400

    /** 把一组 tool-related message 拼成 reasoning 区可读的简短摘要。*/
    fun formatBuffer(buffer: List<Message>): String {
        val sb = StringBuilder()
        for (m in buffer) {
            when (m.role) {
                Message.ROLE_TOOL_CALL -> {
                    val (name, args) = parseFirstToolCall(m.toolCallsJson)
                    if (name.isNotEmpty()) {
                        sb.append("🔧 调用 ").append(name)
                        if (args.isNotEmpty()) {
                            val shown = args.take(ARGS_PREVIEW_MAX).replace('\n', ' ')
                            sb.append("(").append(shown)
                            if (args.length > ARGS_PREVIEW_MAX) sb.append("…")
                            sb.append(")")
                        }
                        sb.append('\n')
                    }
                }
                Message.ROLE_TOOL_RESULT -> {
                    val tn = m.toolName
                    if (tn.isNotEmpty()) sb.append("→ ").append(tn).append(" 返回:\n")
                    val content = m.content ?: ""
                    val truncated = content.take(RESULT_PREVIEW_MAX)
                    sb.append(truncated)
                    if (content.length > RESULT_PREVIEW_MAX) {
                        sb.append("\n…(已截断, 完整结果见工具调用日志)")
                    }
                    sb.append('\n')
                }
            }
        }
        return sb.toString().trimEnd()
    }

    /**
     * 从 `toolCallsJson`（OpenAI 协议的 tool_calls 数组字符串）里取第一项的
     * `function.name` + `function.arguments`。解析失败返回 `("", "")`。
     */
    fun parseFirstToolCall(json: String): Pair<String, String> {
        if (json.isBlank()) return "" to ""
        return try {
            val arr = JsonParser().parse(json).asJsonArray
            if (arr.size() == 0) return "" to ""
            val fn = arr[0].asJsonObject?.getAsJsonObject("function") ?: return "" to ""
            val name = fn.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val args = fn.get("arguments")?.takeIf { !it.isJsonNull }?.asString ?: ""
            name to args
        } catch (_: Exception) {
            "" to ""
        }
    }
}
