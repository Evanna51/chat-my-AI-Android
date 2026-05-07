package com.example.aichat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aichat.sync.RemoteSyncConfigStore
import com.example.aichat.sync.ToolBridge
import com.google.android.material.appbar.MaterialToolbar
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Per-session tool call audit log: shows every role=ROLE_TOOL_CALL (3) and
 * role=ROLE_TOOL_RESULT (4) message in chronological order. Used during
 * debugging to verify whether the LLM actually invoked a registered tool
 * (e.g. search_memory) and what the server returned.
 */
class ToolCallLogActivity : ThemedActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val prettyGson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val parser = JsonParser()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var diagView: TextView
    private val items = ArrayList<Entry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tool_call_log)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        recycler = findViewById(R.id.recyclerToolCallLog)
        emptyView = findViewById(R.id.textEmpty)
        diagView = findViewById(R.id.textDiagnostic)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = Adapter()

        val sid = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        renderDiagnostic(sid)
        if (sid.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            return
        }
        loadEntries(sid)
    }

    /**
     * 实时检测 ToolBridge 链路状态. 用户点开此页就能一眼看出: tools 没注入是哪一环卡了.
     */
    private fun renderDiagnostic(sessionId: String) {
        val cfg = RemoteSyncConfigStore(this)
        val baseUrl = cfg.getBaseUrl()
        val toolEnabled = cfg.isSearchMemoryToolEnabled()
        val boundAssistantId = if (sessionId.isNotEmpty())
            SessionAssistantBindingStore(this).getAssistantId(sessionId) else ""
        val bridge = ToolBridge.build(this, boundAssistantId, sessionId)
        val bridgeReady = bridge != null && bridge.isReady()
        val toolCount = if (bridgeReady) bridge?.toolsJson()?.size() ?: 0 else 0

        val blockReason = when {
            !toolEnabled -> "search_memory 总开关未开"
            baseUrl.isEmpty() -> "服务器地址为空"
            boundAssistantId.isEmpty() -> "当前会话未绑定人设"
            !bridgeReady -> "ToolBridge build 失败 (检查 baseUrl/aid)"
            else -> null
        }

        diagView.text = buildString {
            append("ToolBridge 状态: ")
            append(if (bridgeReady) "✓ 就绪 (注入 $toolCount 个工具)" else "✗ 未注入")
            append('\n')
            if (blockReason != null) {
                append("阻塞原因: ").append(blockReason).append('\n')
            }
            append("─────\n")
            append("search_memory 开关: ").append(if (toolEnabled) "开" else "关").append('\n')
            append("服务器地址: ").append(baseUrl.ifEmpty { "(空)" }).append('\n')
            append("会话 assistantId: ").append(boundAssistantId.ifEmpty { "(未绑定)" }).append('\n')
            if (bridgeReady) {
                append("─────\n")
                append("已注册工具:\n")
                bridge?.toolsJson()?.forEach { el ->
                    val name = el.asJsonObject?.getAsJsonObject("function")?.get("name")?.asString ?: "?"
                    append("  • ").append(name).append('\n')
                }
                append("─────\n")
                append("提示: 若 AI 在该会话未触发工具, 多半是模型本身不支持\n")
                append("function calling, 或选择不调用. 可换 gpt-4o-mini /\n")
                append("DeepSeek-V3 / Claude 3.5 等已知支持的模型测试.")
            }
        }
    }

    private fun loadEntries(sid: String) {
        executor.execute {
            val rows = AppDatabase.getInstance(this).messageDao().getBySession(sid)
            val mapped = rows
                .filter { it.role == Message.ROLE_TOOL_CALL || it.role == Message.ROLE_TOOL_RESULT }
                .map { toEntry(it) }
            runOnUiThread {
                items.clear()
                items.addAll(mapped)
                recycler.adapter?.notifyDataSetChanged()
                emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun toEntry(m: Message): Entry {
        val isCall = m.role == Message.ROLE_TOOL_CALL
        val toolName: String
        val body: String
        if (isCall) {
            // role=3: toolCallsJson 是 OpenAI 风格数组. 取第一项的 function.name + arguments
            val parsed = parseToolCalls(m.toolCallsJson)
            toolName = parsed.first
            body = parsed.second
        } else {
            // role=4: toolName 在字段里, content 是结果 JSON
            toolName = m.toolName.ifEmpty { "(unknown)" }
            body = prettyJsonOrRaw(m.content)
        }
        val timeText = timeFmt.format(Date(m.createdAt))
        val badge = getString(if (isCall) R.string.tool_call_label_call else R.string.tool_call_label_result)
        return Entry(isCall, badge, toolName, timeText, body)
    }

    /** @return Pair(toolName, prettyArguments). 解析失败时返回 ("(parse error)", 原文). */
    private fun parseToolCalls(json: String): Pair<String, String> {
        if (json.isBlank()) return "(empty)" to ""
        return try {
            val arr = parser.parse(json).asJsonArray
            if (arr.size() == 0) return "(empty)" to json
            val first = arr.get(0).asJsonObject
            val fn = first.getAsJsonObject("function")
            val name = fn?.get("name")?.asString ?: "(unknown)"
            val argsRaw = fn?.get("arguments")?.asString.orEmpty()
            val pretty = prettyJsonOrRaw(argsRaw)
            // 多个 tool_call 时, 后续的也展示在 body 末尾
            val body = if (arr.size() > 1) {
                pretty + "\n\n--- 还有 ${arr.size() - 1} 个并行 tool_call ---\n" + prettyJsonOrRaw(json)
            } else pretty
            name to body
        } catch (_: Exception) {
            "(parse error)" to json
        }
    }

    private fun prettyJsonOrRaw(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            prettyGson.toJson(parser.parse(raw))
        } catch (_: Exception) {
            raw
        }
    }

    private data class Entry(
        val isCall: Boolean,
        val badge: String,
        val toolName: String,
        val time: String,
        val body: String,
    )

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tool_call_log, parent, false)
            return Holder(v)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
        override fun getItemCount(): Int = items.size
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val badge: TextView = view.findViewById(R.id.textRoleBadge)
        private val name: TextView = view.findViewById(R.id.textToolName)
        private val time: TextView = view.findViewById(R.id.textTime)
        private val body: TextView = view.findViewById(R.id.textBody)

        fun bind(e: Entry) {
            badge.text = e.badge
            name.text = e.toolName
            time.text = e.time
            body.text = e.body
            body.visibility = if (e.body.isEmpty()) View.GONE else View.VISIBLE
        }
    }
}
