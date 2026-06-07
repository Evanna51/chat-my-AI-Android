package com.example.aichat.inkos

import android.content.Context
import com.example.aichat.sync.RemoteSyncConfigStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * InkOS Studio (本地 npm 包 @actalk/inkos) 的最小客户端。
 *
 * R7 接入 inkos 生成器前的过渡: 先用 [probe] 验证后端可达,
 * 之后把生成调用堆到这个类里、走 OkHttp 复用现有连接池。
 *
 * 局域网开放性: inkos studio 默认 `serve({port: 4567})` 由
 * @hono/node-server 绑全部接口 (0.0.0.0/::), 同 Wi-Fi 即可直连。
 * 控制台 "running on http://localhost" 是日志误导, 不是真实 bind 地址。
 *
 * BASE_URL 来源: inkos 与 wi-chat-server 跑在同一台机器, 只是端口不同。
 * 通过 [init] 注入 appContext, 之后 BASE_URL 会从 [RemoteSyncConfigStore.getBaseUrl]
 * 取 host, 端口换成 [INKOS_PORT]。手动切换居家/外出场景时, BASE_URL
 * 会自动跟着远程服务地址变 (远程服务读 ModelConfig.activeScene)。
 * 远程服务未配置时回落到 [FALLBACK_BASE_URL]。
 */
object InkosClient {

    /** Mac Wi-Fi 内网 IP, 仅在远程服务未配置时作为兜底使用。 */
    private const val FALLBACK_BASE_URL = "http://192.168.5.7:4567"

    /** inkos studio 监听端口, 由 npm 包默认 `serve({port: 4567})` 决定。 */
    private const val INKOS_PORT = 4567

    @Volatile private var appContext: Context? = null

    /** 由 AIChatApp.onCreate 调用一次, 注入 application context。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 动态拼装的 inkos 地址: 复用远程服务的 host, 端口换成 [INKOS_PORT]。
     * 每次访问都重新读 (而非缓存), 这样模型配置切场景后立刻生效。
     */
    val BASE_URL: String
        get() = computeBaseUrl()

    private fun computeBaseUrl(): String {
        val ctx = appContext ?: return FALLBACK_BASE_URL
        val remote = try {
            RemoteSyncConfigStore(ctx).getBaseUrl()
        } catch (_: Exception) {
            ""
        }
        if (remote.isEmpty()) return FALLBACK_BASE_URL
        val parsed = remote.toHttpUrlOrNull() ?: return FALLBACK_BASE_URL
        return "${parsed.scheme}://${parsed.host}:$INKOS_PORT"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** [createBook] 结果。bookId 非空表示已发起异步创建,后续可走 SSE / 状态查询。 */
    data class BookCreateResult(
        val ok: Boolean,
        val bookId: String?,
        val errorMessage: String?,
    )

    /**
     * 探测后端是否在线。同步阻塞调用 — 在 background executor 里跑,
     * 不要在主线程调。
     *
     * 用 `/api/v1/books` 是因为:
     *  - 它一定走 inkos 自己的路由, 返回 `{"books":[...]}`, 普通 web server 不会撞
     *  - 不会有副作用 (纯查询)
     *  - 返回体很小, 即使没书也是 `{"books":[]}`
     */
    fun probe(): Boolean {
        return try {
            val req = Request.Builder()
                .url("$BASE_URL/api/v1/books")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body?.string() ?: return false
                body.contains("\"books\"")
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 在 inkos 创建一本新书。同步阻塞,在 background executor 里跑。
     *
     * inkos 端实际是异步流程:server 立刻返回 `{status:"creating", bookId}`,真正的 pipeline
     * 在后台跑,通过 `/api/v1/books/{id}/create-status` 和 SSE `book:created` 通知完成。
     * 这里只负责发起,返回 bookId 后上层可决定是否轮询。
     *
     * @param blurb 把大纲文本塞进 blurb 字段,inkos 会把它喂给 create_book 的 LLM
     *              用来生成 story bible / outline 等初始资料。
     */
    fun createBook(
        title: String,
        blurb: String,
        genre: String = "other",
        language: String = "zh",
        targetChapters: Int? = null,
        chapterWordCount: Int? = null,
    ): BookCreateResult {
        if (title.isBlank()) {
            return BookCreateResult(false, null, "标题不能为空")
        }
        val payload = JsonObject().apply {
            addProperty("title", title.trim())
            addProperty("genre", genre)
            addProperty("language", language)
            if (blurb.isNotBlank()) addProperty("blurb", blurb)
            if (targetChapters != null) addProperty("targetChapters", targetChapters)
            if (chapterWordCount != null) addProperty("chapterWordCount", chapterWordCount)
        }
        return try {
            val req = Request.Builder()
                .url("$BASE_URL/api/v1/books/create")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = parseErrorMessage(bodyStr) ?: "HTTP ${resp.code}"
                    return BookCreateResult(false, null, msg)
                }
                val obj = runCatching { JsonParser().parse(bodyStr).asJsonObject }.getOrNull()
                val bookId = obj?.get("bookId")?.asString
                if (bookId.isNullOrEmpty()) {
                    BookCreateResult(false, null, "inkos 未返回 bookId: $bodyStr")
                } else {
                    BookCreateResult(true, bookId, null)
                }
            }
        } catch (e: Exception) {
            BookCreateResult(false, null, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 在 inkos 端创建 session 并绑到 bookId。
     * 让 studio UI 的 session 导航能看到这本书 (POST /books/create 不会自动建 session)。
     * 返回 inkos 端生成的 sessionId (格式 `<timestamp>-<rand>`),失败返回 null。
     */
    fun createBookSession(bookId: String): String? {
        if (bookId.isBlank()) return null
        val payload = JsonObject().apply {
            addProperty("bookId", bookId)
        }
        return try {
            val req = Request.Builder()
                .url("$BASE_URL/api/v1/sessions")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                JsonParser().parse(body).asJsonObject
                    .get("session")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("sessionId")?.takeIf { !it.isJsonNull }?.asString
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * `GET /api/v1/books/:id/chapters/:num` 返回 `{chapterNumber, filename, content}`。
     * 同步阻塞。content 即章节正文 markdown。失败/不存在返回 null。
     */
    fun fetchChapter(bookId: String, num: Int): JsonObject? {
        if (bookId.isBlank() || num <= 0) return null
        return try {
            val req = Request.Builder()
                .url("$BASE_URL/api/v1/books/${bookId}/chapters/${num}")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                JsonParser().parse(body).asJsonObject
            }
        } catch (_: Exception) {
            null
        }
    }

    /** `GET /api/v1/books/:id` 返回 `{book, chapters, nextChapter}`,失败返回 null。同步阻塞。 */
    fun getBook(bookId: String): JsonObject? {
        if (bookId.isBlank()) return null
        return try {
            val req = Request.Builder()
                .url("$BASE_URL/api/v1/books/${bookId}")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                JsonParser().parse(body).asJsonObject
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * `GET /api/v1/books/:id/create-status`:
     *  - 进行中 → `{status:"creating"}`
     *  - 出错  → `{status:"error", error}`
     *  - 完成后 server 端 entry 删掉, 返回 404 (这里映射为 null)
     */
    fun getCreateStatus(bookId: String): JsonObject? {
        if (bookId.isBlank()) return null
        return try {
            val req = Request.Builder()
                .url("$BASE_URL/api/v1/books/${bookId}/create-status")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                JsonParser().parse(body).asJsonObject
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── 写下一章 / 审计 / 编辑 ─────────────────────────────────

    /**
     * 让 inkos 写下一章 (fire-and-forget)。
     * `POST /api/v1/books/:id/write-next` 立刻返回 `{status:"writing"}`,
     * 真正写章在后台,通过 SSE `write:start` → `tool:*` → `draft:delta` → `write:complete`/`write:error` 推。
     * @param wordCount 可选,覆盖 book.json 里的章节字数预算。null = 用 book 默认。
     */
    fun writeNext(bookId: String, wordCount: Int? = null): BookCreateResult {
        if (bookId.isBlank()) return BookCreateResult(false, null, "bookId 为空")
        val payload = JsonObject().apply {
            if (wordCount != null) addProperty("wordCount", wordCount)
        }
        return try {
            val req = Request.Builder()
                .url("$BASE_URL/api/v1/books/${bookId}/write-next")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return BookCreateResult(false, null, parseErrorMessage(bodyStr) ?: "HTTP ${resp.code}")
                }
                BookCreateResult(true, bookId, null)
            }
        } catch (e: Exception) {
            BookCreateResult(false, null, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 审计已写章节 (同步阻塞,LLM 调用,慢)。
     * `POST /api/v1/books/:id/audit/:chapter` 等审计完才返回结果。
     * 在 background executor 跑;返回 inkos 的原始 JSON 给上层格式化展示。
     */
    fun auditChapter(bookId: String, chapter: Int): JsonObject? {
        if (bookId.isBlank() || chapter <= 0) return null
        return try {
            val req = Request.Builder()
                .url("$BASE_URL/api/v1/books/${bookId}/audit/${chapter}")
                .post("{}".toRequestBody(JSON))
                .build()
            // 审计可能花几分钟,临时放宽 read timeout
            val auditClient = client.newBuilder().readTimeout(5, TimeUnit.MINUTES).build()
            auditClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: return null
                JsonParser().parse(bodyStr).asJsonObject
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 编辑书籍元信息 (同步)。`PUT /api/v1/books/:id`
     * 支持: `chapterWordCount` / `targetChapters` / `status` / `language`。
     */
    fun editBook(bookId: String, updates: JsonObject): JsonObject? {
        if (bookId.isBlank()) return null
        return try {
            val req = Request.Builder()
                .url("$BASE_URL/api/v1/books/${bookId}")
                .put(updates.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: return null
                if (!resp.isSuccessful) return null
                JsonParser().parse(bodyStr).asJsonObject
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseErrorMessage(body: String): String? {
        return runCatching {
            val obj = JsonParser().parse(body).asJsonObject
            obj.get("error")?.asString
        }.getOrNull()
    }
}
