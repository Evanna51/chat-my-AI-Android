package com.example.aichat

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.aichat.inkos.InkosClient
import com.example.aichat.inkos.InkosEventBus
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 「查看书籍信息」: writer 模式专属。读 session 绑定的 inkosBookId,
 * 通过 inkos studio REST 查询书籍元信息 + 章节列表 + 建书状态,
 * 并订阅 SSE 在建书进行中实时刷新。
 *
 * 数据源:
 *  - `GET /api/v1/books/:id` → book + chapters + nextChapter
 *  - `GET /api/v1/books/:id/create-status` → creating / error / (404=done)
 *  - SSE `book:created` / `book:error` / `write:complete` 等 → 触发 reload
 */
class BookInfoActivity : ThemedActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }

    private var sessionId: String = ""
    private var bookId: String = ""
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var container: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var emptyText: TextView
    private lateinit var eventCard: MaterialCardView
    private lateinit var eventScroll: ScrollView
    private lateinit var eventText: TextView

    /** 滚动日志环形缓冲。inkos 事件大多没 bookId,所以只能用「单用户单建」假设全收。 */
    private val eventLog = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * inkos 的 thinking:delta / draft:delta 是字符级流,频率太高直接 append 会卡主线程。
     * 这里按事件 key 把流式 delta 累计到一个滚动 buffer,只保留尾巴 60 字。
     */
    private val streamBuffers = HashMap<String, StringBuilder>()

    private val sseListener = object : InkosEventBus.Listener {
        override fun onEvent(event: String, data: JsonObject) {
            val id = data.get("bookId")?.takeIf { !it.isJsonNull }?.asString
            if (event.startsWith("book:")) {
                if (id == bookId) reload()
                if (id == bookId) appendEvent(event, data)
                return
            }
            // 章节写完 → 自动拉进聊天 (后台线程)
            if (event == "write:complete" && id == bookId) {
                val num = data.get("chapterNumber")?.takeIf { !it.isJsonNull }?.asInt
                val status = data.get("status")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val title = data.get("title")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                if (num != null) pullChapterIntoChat(num, title, status, isAuto = true)
                reload()
            }
            appendEvent(event, data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_info)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        container = findViewById(R.id.contentContainer)
        statusText = findViewById(R.id.textBookStatus)
        emptyText = findViewById(R.id.textEmpty)
        eventCard = findViewById(R.id.cardEventStream)
        eventScroll = findViewById(R.id.scrollEventStream)
        eventText = findViewById(R.id.textEventStream)
        findViewById<View>(R.id.btnClearEventStream).setOnClickListener {
            eventLog.clear()
            streamBuffers.clear()
            eventText.text = ""
        }

        val opts = SessionChatOptionsStore(this).get(sessionId)
        bookId = opts.inkosBookId.trim()

        // 操作按钮 — 只有绑了 bookId 才能用
        if (bookId.isNotEmpty()) {
            findViewById<View>(R.id.rowBookActions).visibility = View.VISIBLE
            findViewById<MaterialButton>(R.id.btnWriteNext).setOnClickListener { triggerWriteNext() }
            findViewById<MaterialButton>(R.id.btnAuditChapter).setOnClickListener { promptAuditChapter() }
            findViewById<MaterialButton>(R.id.btnEditBook).setOnClickListener { promptEditBook() }
            findViewById<MaterialButton>(R.id.btnPullChapter).setOnClickListener { promptPullChapter() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (bookId.isBlank()) {
            renderEmpty("尚未绑定 inkos 书籍。\n在大纲页打开 Ink toggle,点「章节计划」即建书。")
            return
        }
        reload()
        InkosEventBus.addListener(sseListener)
    }

    override fun onPause() {
        super.onPause()
        InkosEventBus.removeListener(sseListener)
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun reload() {
        executor.execute {
            val book = InkosClient.getBook(bookId)
            val createStatus = InkosClient.getCreateStatus(bookId)
            runOnUiThread { render(book, createStatus) }
        }
    }

    private fun renderEmpty(message: String) {
        container.visibility = View.GONE
        statusText.visibility = View.GONE
        emptyText.text = message
        emptyText.visibility = View.VISIBLE
    }

    private fun render(bookResp: JsonObject?, createStatus: JsonObject?) {
        val isCreating = createStatus?.get("status")?.takeIf { !it.isJsonNull }?.asString == "creating"

        // 建书中 book.json 还没落盘, GET /books/:id 会返回 404 (bookResp=null) —
        // 这种情况是**正常的**, 不能当作"书不存在"显示空态。只有既不在建也读不到书才空态。
        if (bookResp == null && !isCreating) {
            renderEmpty("无法连接 inkos 或书籍不存在 ($bookId)")
            return
        }

        emptyText.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        container.visibility = View.VISIBLE
        eventCard.visibility = if (isCreating) View.VISIBLE else View.GONE

        statusText.text = buildStatusBanner(createStatus)
        container.removeAllViews()

        if (bookResp != null) {
            val book = bookResp.get("book")?.takeIf { it.isJsonObject }?.asJsonObject
            val chapters = bookResp.get("chapters")?.takeIf { it.isJsonArray }?.asJsonArray
            val nextChapter = bookResp.get("nextChapter")?.takeIf { !it.isJsonNull }?.asInt
            if (book != null) renderBookSection(book)
            renderChaptersSection(chapters, nextChapter)
        } else {
            // creating + 还没 book.json — 顶部事件流是主要看点
            addLabel("书籍信息")
            addText("(inkos 正在建书,book.json 尚未落盘。完成后此处自动刷新出元信息与章节列表。当前可看顶部「实时事件流」卡片观察进度。)")
        }
    }

    private fun buildStatusBanner(createStatus: JsonObject?): String {
        if (createStatus == null) return "状态: 已完成 / 空闲"
        val s = createStatus.get("status")?.asString ?: "unknown"
        return when (s) {
            "creating" -> "状态: 建书中…"
            "error" -> {
                val err = createStatus.get("error")?.takeIf { !it.isJsonNull }?.asString ?: "未知"
                "状态: 建书出错 — $err"
            }
            else -> "状态: $s"
        }
    }

    private fun renderBookSection(book: JsonObject) {
        addLabel("书籍信息")
        val fields = listOf(
            "ID" to book.opt("id"),
            "标题" to book.opt("title"),
            "类型" to book.opt("genre"),
            "状态" to book.opt("status"),
            "语言" to book.opt("language"),
            "平台" to book.opt("platform"),
            "目标章节" to book.opt("targetChapters"),
            "章节字数" to book.opt("chapterWordCount"),
            "创建时间" to book.opt("createdAt"),
            "更新时间" to book.opt("updatedAt"),
        )
        val kv = StringBuilder()
        for ((k, v) in fields) {
            if (v.isBlank()) continue
            kv.append(k).append(": ").append(v).append("\n")
        }
        addText(kv.toString().trimEnd())
    }

    private fun renderChaptersSection(chapters: com.google.gson.JsonArray?, nextChapter: Int?) {
        addLabel("章节 (${chapters?.size() ?: 0}${if (nextChapter != null) " / 下一章: $nextChapter" else ""})")
        if (chapters == null || chapters.size() == 0) {
            addText("(尚无章节)")
            return
        }
        val sb = StringBuilder()
        for (el in chapters) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val num = o.opt("number").ifEmpty { o.opt("chapterNumber") }
            val title = o.opt("title").ifEmpty { "(无标题)" }
            val state = o.opt("state").ifEmpty { o.opt("status") }
            sb.append(num.padStart(4, '0')).append(" ").append(title)
            if (state.isNotEmpty()) sb.append(" [").append(state).append("]")
            sb.append("\n")
        }
        addText(sb.toString().trimEnd())
    }

    private fun addLabel(label: String) {
        val tv = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(getColor(R.color.ios_section_label))
            setPadding(dp(20), dp(16), dp(20), dp(6))
        }
        container.addView(tv)
    }

    private fun addText(content: String) {
        val tv = TextView(this).apply {
            text = content
            textSize = 14f
            setTextColor(getColor(R.color.list_title_color))
            setPadding(dp(20), 0, dp(20), dp(8))
            setTextIsSelectable(true)
        }
        container.addView(tv)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ── 事件流格式化 + 渲染 ───────────────────────────────────────────

    private fun appendEvent(event: String, data: JsonObject) {
        val formatted = formatEvent(event, data) ?: return
        eventCard.visibility = View.VISIBLE
        val line = "${timeFmt.format(Date())} $formatted"
        eventLog.addLast(line)
        while (eventLog.size > 200) eventLog.removeFirst()
        eventText.text = eventLog.joinToString("\n")
        eventScroll.post { eventScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * 把一条事件做成简洁可读的一行。返回 null 表示这条事件没值得展示的内容,跳过。
     *
     *  agent:start    ▶ create_book
     *  agent:complete ✓ create_book
     *  tool:start     🔧 sub_agent(writer)
     *  tool:end       ✓ sub_agent
     *  tool:end:err   ✗ sub_agent (error)
     *  thinking:*     🧠 开始思考  / 思考: …尾部60字
     *  draft:delta    ✍ 写作: …尾部60字
     *  log            [tag] message
     *  llm:progress   忽略 (太密)
     *  book:creating  📕 建书开始
     *  book:created   ✅ 建书完成
     *  book:error     ❌ 错误: …
     */
    private fun formatEvent(event: String, data: JsonObject): String? {
        return when (event) {
            "log" -> {
                val tag = data.opt("tag")
                val msg = data.opt("message")
                if (msg.isEmpty()) null
                else if (tag.isNotEmpty()) "[$tag] ${msg.take(120)}"
                else msg.take(120)
            }
            "agent:start" -> "▶ ${data.opt("instruction").take(80)}"
            "agent:complete" -> "✓ agent 完成"
            "agent:error" -> "✗ agent 错误: ${data.opt("error").take(120)}"
            "tool:start" -> {
                val tool = data.opt("tool")
                val sub = data.get("args")?.takeIf { it.isJsonObject }?.asJsonObject?.opt("agent").orEmpty()
                if (sub.isNotEmpty()) "🔧 $tool($sub)" else "🔧 $tool"
            }
            "tool:update" -> {
                val tool = data.opt("tool")
                val stage = data.opt("stage").ifEmpty { data.opt("phase") }
                if (stage.isNotEmpty()) "  · $tool: $stage" else null
            }
            "tool:end" -> {
                val tool = data.opt("tool")
                val isError = data.get("isError")?.takeIf { !it.isJsonNull }?.asBoolean == true
                if (isError) "✗ $tool (失败)" else "✓ $tool"
            }
            "thinking:start" -> { streamBuffers.remove("thinking"); "🧠 开始思考" }
            "thinking:end" -> { streamBuffers.remove("thinking"); "🧠 思考完成" }
            "thinking:delta" -> {
                appendStream("thinking", data.opt("text"))?.let { "🧠 $it" }
            }
            "draft:start" -> { streamBuffers.remove("draft"); "✍ 开始写作" }
            "draft:complete" -> { streamBuffers.remove("draft"); "✍ 写作完成 (ch.${data.opt("chapterNumber")}, ${data.opt("wordCount")}字)" }
            "draft:error" -> "✗ 写作失败: ${data.opt("error").take(120)}"
            "draft:delta" -> appendStream("draft", data.opt("text"))?.let { "✍ $it" }
            "book:creating" -> "📕 建书开始 (${data.opt("title")})"
            "book:created" -> "✅ 建书完成"
            "book:error" -> "❌ 建书失败: ${data.opt("error").take(120)}"
            "llm:progress" -> null // 太密, 跳过
            else -> "· $event ${data.toString().take(80)}"
        }
    }

    /** thinking/draft delta 是字符级流, 按 key 累计, 显示尾部 60 字。 */
    private fun appendStream(key: String, delta: String): String? {
        if (delta.isEmpty()) return null
        val buf = streamBuffers.getOrPut(key) { StringBuilder() }
        buf.append(delta)
        val tail = if (buf.length > 60) "…${buf.takeLast(60)}" else buf.toString()
        return tail.replace("\n", "⏎")
    }

    private fun JsonObject.opt(key: String): String {
        val el: JsonElement? = get(key)
        if (el == null || el.isJsonNull) return ""
        if (el.isJsonPrimitive) return el.asString
        return el.toString()
    }

    // ── 拉章节进聊天 ─────────────────────────────────────────────

    /**
     * 把指定章节内容作为 assistant 消息插到本 session 的 chat 里。
     * 用户回到 chat 页面即可看到章节正文。
     *
     * @param status inkos 给的章节状态 ("approved" / "audit-failed" / etc)。非 approved 时
     *               消息前会加 "[未过审]" 前缀, 提示用户内容质量有问题。
     * @param isAuto true=SSE 自动触发, false=用户手动点按钮。auto 模式 toast 短些。
     */
    private fun pullChapterIntoChat(num: Int, title: String, status: String, isAuto: Boolean) {
        if (sessionId.isBlank()) return
        executor.execute {
            val chapter = InkosClient.fetchChapter(bookId, num)
            val content = chapter?.opt("content").orEmpty()
            if (content.isBlank()) {
                runOnUiThread {
                    if (!isAuto) Toast.makeText(this, "拉章节失败: 内容为空 ($num)", Toast.LENGTH_SHORT).show()
                }
                return@execute
            }

            val auditFailed = status.isNotEmpty() && status != "approved" && status != "done"
            val header = buildString {
                if (auditFailed) append("[未过审] ")
                append("第 ").append(num).append(" 章")
                if (title.isNotEmpty()) append(" 《").append(title).append("》")
                if (auditFailed) append(" (status=").append(status).append(")")
                append("\n\n")
            }
            val finalText = header + content.trim()

            val msg = Message().apply {
                this.sessionId = this@BookInfoActivity.sessionId
                this.role = Message.ROLE_ASSISTANT
                this.content = finalText
                this.createdAt = System.currentTimeMillis()
                // 标记为已 synced 避免后台同步把这条 push 给 server (这是本地 inkos 来源)
                this.synced = 1
                // assistantId 取 session 绑的, 让 chat UI 渲染助手头像
                val asId = SessionAssistantBindingStore(this@BookInfoActivity).getAssistantId(this.sessionId)
                if (asId.isNotEmpty()) this.assistantId = asId
            }
            try {
                AppDatabase.getInstance(this).messageDao().insert(msg)
                runOnUiThread {
                    val prefix = if (isAuto) "Ink 完成第 $num 章" else "已拉第 $num 章"
                    val suffix = if (auditFailed) " (未过审)" else ""
                    Toast.makeText(this, "$prefix$suffix, 已写入聊天", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "插入聊天失败: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 手动拉:弹出现有章节列表 → 用户选 → 拉到 chat。补 SSE 漏推的章。 */
    private fun promptPullChapter() {
        executor.execute {
            val book = InkosClient.getBook(bookId)
            val chapters = book?.get("chapters")?.takeIf { it.isJsonArray }?.asJsonArray
            runOnUiThread {
                if (chapters == null || chapters.size() == 0) {
                    Toast.makeText(this, "暂无已写章节可拉", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val items = mutableListOf<Triple<Int, String, String>>() // (num, title, status)
                for (el in chapters) {
                    if (!el.isJsonObject) continue
                    val o = el.asJsonObject
                    val n = o.opt("number").ifEmpty { o.opt("chapterNumber") }.toIntOrNull() ?: continue
                    items.add(Triple(n, o.opt("title").ifEmpty { "(无标题)" }, o.opt("status")))
                }
                val labels = items.map { (n, t, s) ->
                    val tag = if (s.isNotEmpty() && s != "approved" && s != "done") " [$s]" else ""
                    "第 $n 章 《$t》$tag"
                }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle("拉章节进聊天")
                    .setItems(labels) { _, which ->
                        val (n, t, s) = items[which]
                        pullChapterIntoChat(n, t, s, isAuto = false)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    // ── 三个操作: 写下一章 / 审计 / 编辑 ─────────────────────────────

    private fun triggerWriteNext() {
        Toast.makeText(this, "Ink 开始写下一章…", Toast.LENGTH_SHORT).show()
        executor.execute {
            val result = InkosClient.writeNext(bookId)
            runOnUiThread {
                if (!result.ok) {
                    Toast.makeText(this, "写下一章发起失败: ${result.errorMessage ?: "未知"}", Toast.LENGTH_LONG).show()
                } else {
                    // 服务端立刻返回 writing,真正完成走 SSE write:complete; 监听已通过 BookInfoActivity 的 sseListener 在收
                    // 强制把事件卡片打开方便观察
                    eventCard.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun promptAuditChapter() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "章节号 (e.g. 1)"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("审计章节")
            .setMessage("inkos 会调用 LLM 审计指定章节的连贯性, 耗时较长。")
            .setView(input)
            .setPositiveButton("开始") { _, _ ->
                val n = input.text?.toString()?.trim()?.toIntOrNull()
                if (n == null || n <= 0) {
                    Toast.makeText(this, "章节号不合法", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                runAudit(n)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runAudit(chapter: Int) {
        Toast.makeText(this, "Ink 开始审计第 $chapter 章 (慢, 等几分钟)…", Toast.LENGTH_LONG).show()
        executor.execute {
            val result = InkosClient.auditChapter(bookId, chapter)
            runOnUiThread { showAuditResult(chapter, result) }
        }
    }

    private fun showAuditResult(chapter: Int, result: JsonObject?) {
        if (result == null) {
            Toast.makeText(this, "审计失败 (网络/超时/不存在)", Toast.LENGTH_LONG).show()
            return
        }
        val pretty = try {
            com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result)
        } catch (_: Exception) {
            result.toString()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("第 $chapter 章 审计结果")
            .setMessage(pretty.take(4000)) // 防超长
            .setPositiveButton("确定", null)
            .show()
    }

    private fun promptEditBook() {
        // 现有元信息回显, 用户改完保存
        executor.execute {
            val book = InkosClient.getBook(bookId)?.get("book")?.takeIf { it.isJsonObject }?.asJsonObject
            runOnUiThread {
                if (book == null) {
                    Toast.makeText(this, "无法读取书籍当前信息", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                showEditBookDialog(book)
            }
        }
    }

    private fun showEditBookDialog(book: JsonObject) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        fun row(label: String, init: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            val l = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(getColor(R.color.ios_section_label))
                setPadding(0, dp(8), 0, dp(2))
            }
            val e = EditText(this).apply {
                inputType = type
                setText(init)
            }
            container.addView(l)
            container.addView(e)
            return e
        }
        val editTitle = row("标题", book.opt("title"))
        val editTarget = row("目标章数", book.opt("targetChapters"), InputType.TYPE_CLASS_NUMBER)
        val editWords = row("每章字数", book.opt("chapterWordCount"), InputType.TYPE_CLASS_NUMBER)
        val editStatus = row("状态 (outlining/writing/done)", book.opt("status"))

        MaterialAlertDialogBuilder(this)
            .setTitle("编辑书籍")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val updates = JsonObject()
                editTarget.text.toString().trim().toIntOrNull()?.let { updates.addProperty("targetChapters", it) }
                editWords.text.toString().trim().toIntOrNull()?.let { updates.addProperty("chapterWordCount", it) }
                editStatus.text.toString().trim().takeIf { it.isNotEmpty() }?.let { updates.addProperty("status", it) }
                // PUT /books/:id 不支持改 title(后端代码里不接受),只能改这几项;title 编辑框只是给用户对照看
                if (updates.size() == 0) {
                    Toast.makeText(this, "无改动", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                executor.execute {
                    val resp = InkosClient.editBook(bookId, updates)
                    runOnUiThread {
                        if (resp != null) {
                            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                            reload()
                        } else {
                            Toast.makeText(this, "保存失败", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
