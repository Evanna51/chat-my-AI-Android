package com.example.aichat.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.aichat.AppDatabase
import com.example.aichat.Message
import com.example.aichat.MyAssistantStore
import com.example.aichat.ProactiveMessageNotifier
import com.example.aichat.SessionAssistantBindingStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent WebSocket connection to wi-chat-server `/api/ws`.
 *
 * Inbound only (this revision):
 *   - `proactive` frame    → 写一条 ROLE_ASSISTANT message + 本地通知 + ack
 *   - `queued_batch` frame → 同上，逐条 ack；id 在内存 seen 集合里去重
 *   - `hello` / `pong`     → 心跳/握手回执，no-op
 *   - `server_shutdown`    → 等下一次 onClosed 触发 reconnect
 *
 * Outbound:
 *   - `ping` 应用层心跳 25s 周期
 *   - `ack`  收到 proactive / queued_batch 每条都回 received
 *
 * 自动重连: 指数退避 1s → 30s 封顶, OkHttp socket 错误或 server close 都会触发.
 *
 * 仅在 [RemoteSyncConfigStore.isEnabled] + 非空 baseUrl 时才连. shutdown() 后
 * 不再自动重连, 直到下次 [start] 调用.
 */
object WsClient {

    private const val TAG = "WsClient"
    private const val PING_INTERVAL_MS = 25_000L
    private const val INITIAL_RECONNECT_MS = 1_000L
    private const val MAX_RECONNECT_MS = 30_000L
    private const val SEEN_CACHE_LIMIT = 500
    /** Default user id used when no client-side multi-user system is set up. */
    private const val DEFAULT_USER_ID = "default-user"

    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var appContext: Context? = null
    private var ws: WebSocket? = null
    private var httpClient: OkHttpClient? = null
    private var reconnectDelay = INITIAL_RECONNECT_MS

    private val seen = LinkedHashSet<String>()

    private val pingRunnable = object : Runnable {
        override fun run() {
            val socket = ws ?: return
            try {
                socket.send(JsonObject().apply {
                    addProperty("op", "ping")
                    addProperty("ts", System.currentTimeMillis())
                }.toString())
            } catch (_: Exception) {}
            mainHandler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    private val reconnectRunnable = Runnable {
        if (!running.get()) return@Runnable
        attemptConnect()
    }

    /** Idempotent. Call from AIChatApp.onCreate or wherever sync is enabled. */
    fun start(context: Context) {
        if (running.getAndSet(true)) return
        appContext = context.applicationContext
        reconnectDelay = INITIAL_RECONNECT_MS
        attemptConnect()
    }

    fun isOpen(): Boolean = ws != null

    /**
     * Send `message_create` for a freshly inserted user/assistant turn.
     * Best-effort: returns false when no live socket. server 应答的 `message_persisted`
     * 回执会异步把对应 turn 标 synced=1, drainer 自然不会再推 sync_push.
     */
    fun sendMessageCreate(
        turnId: String,
        assistantId: String,
        sessionId: String,
        role: String,
        content: String,
        createdAt: Long,
        toolCallsJson: String? = null,
        toolCallId: String? = null,
        toolName: String? = null,
    ): Boolean {
        val socket = ws ?: return false
        if (turnId.isEmpty() || assistantId.isEmpty() || role.isEmpty()) return false
        val frame = JsonObject().apply {
            addProperty("op", "message_create")
            addProperty("id", turnId)
            addProperty("assistantId", assistantId)
            addProperty("sessionId", sessionId)
            addProperty("role", role)
            addProperty("content", content)
            addProperty("createdAt", if (createdAt > 0) createdAt else System.currentTimeMillis())
            if (!toolCallsJson.isNullOrEmpty()) addProperty("toolCallsJson", toolCallsJson)
            if (!toolCallId.isNullOrEmpty()) addProperty("toolCallId", toolCallId)
            if (!toolName.isNullOrEmpty()) addProperty("toolName", toolName)
        }
        return try {
            socket.send(frame.toString())
        } catch (_: Exception) { false }
    }

    /**
     * Send `message_update` for an edited turn. server 会 re-embed memory.
     */
    fun sendMessageUpdate(turnId: String, content: String, assistantId: String?): Boolean {
        val socket = ws ?: return false
        if (turnId.isEmpty()) return false
        val frame = JsonObject().apply {
            addProperty("op", "message_update")
            addProperty("id", turnId)
            addProperty("content", content)
            if (!assistantId.isNullOrEmpty()) addProperty("assistantId", assistantId)
        }
        return try {
            socket.send(frame.toString())
        } catch (_: Exception) { false }
    }

    /** Stop and don't auto-reconnect. */
    fun shutdown() {
        if (!running.getAndSet(false)) return
        mainHandler.removeCallbacks(pingRunnable)
        mainHandler.removeCallbacks(reconnectRunnable)
        try { ws?.close(1000, "client_shutdown") } catch (_: Exception) {}
        ws = null
    }

    private fun attemptConnect() {
        val ctx = appContext ?: return
        val cfg = RemoteSyncConfigStore(ctx)
        if (!cfg.isEnabled()) {
            Log.d(TAG, "sync disabled, not connecting")
            return
        }
        val baseUrl = cfg.getBaseUrl().trim()
        if (baseUrl.isEmpty()) {
            Log.d(TAG, "baseUrl empty, not connecting")
            return
        }
        val wsUrl = toWsUrl(baseUrl, DEFAULT_USER_ID, cfg.getApiKey()) ?: run {
            Log.w(TAG, "cannot derive ws url from baseUrl=$baseUrl")
            return
        }

        // pingInterval=0 disables OkHttp's TCP-level ping; we use app-level JSON ping instead.
        val client = httpClient ?: OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // never timeout — long-lived
            .pingInterval(0, TimeUnit.SECONDS)
            .build()
            .also { httpClient = it }

        val req = Request.Builder().url(wsUrl).build()
        Log.d(TAG, "connecting $wsUrl")
        ws = client.newWebSocket(req, listener)
    }

    private fun toWsUrl(httpBase: String, userId: String, apiKey: String): String? {
        val trimmed = httpBase.trimEnd('/')
        val scheme = when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            else -> return null
        }
        val sb = StringBuilder(scheme).append("/api/ws?userId=").append(urlEncode(userId))
        if (apiKey.isNotEmpty()) sb.append("&apiKey=").append(urlEncode(apiKey))
        return sb.toString()
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    private fun scheduleReconnect() {
        if (!running.get()) return
        mainHandler.postDelayed(reconnectRunnable, reconnectDelay)
        Log.d(TAG, "scheduled reconnect in ${reconnectDelay}ms")
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_MS)
    }

    private fun startPing() {
        mainHandler.removeCallbacks(pingRunnable)
        mainHandler.postDelayed(pingRunnable, PING_INTERVAL_MS)
    }

    private fun stopPing() {
        mainHandler.removeCallbacks(pingRunnable)
    }

    /** Add to seen, evict oldest beyond cap. Returns true if newly added. */
    private fun markSeen(id: String): Boolean {
        if (id.isEmpty()) return false
        synchronized(seen) {
            if (seen.contains(id)) return false
            seen.add(id)
            while (seen.size > SEEN_CACHE_LIMIT) {
                val it = seen.iterator()
                if (it.hasNext()) { it.next(); it.remove() }
            }
            return true
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "onOpen ${response.code}")
            reconnectDelay = INITIAL_RECONNECT_MS
            startPing()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            executor.execute { handleFrame(webSocket, text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "onClosing $code $reason")
            try { webSocket.close(1000, null) } catch (_: Exception) {}
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "onClosed $code $reason")
            stopPing()
            ws = null
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "onFailure ${t.message ?: ""}")
            stopPing()
            ws = null
            scheduleReconnect()
        }
    }

    private fun handleFrame(socket: WebSocket, raw: String) {
        val obj = try {
            JsonParser().parse(raw).asJsonObject
        } catch (_: Exception) {
            Log.w(TAG, "bad frame, not json: ${raw.take(120)}")
            return
        }
        val op = obj.get("op")?.asString.orEmpty()
        when (op) {
            "hello" -> {} // no-op
            "pong" -> {}
            "server_shutdown" -> Log.d(TAG, "server_shutdown received, will reconnect on close")
            "proactive" -> handleProactive(socket, obj)
            "queued_batch" -> handleQueuedBatch(socket, obj)
            "message_persisted" -> handleMessagePersisted(obj)
            "message_updated" -> handleMessageUpdated(obj)
            else -> Log.d(TAG, "unhandled op=$op")
        }
    }

    private fun handleProactive(socket: WebSocket, frame: JsonObject) {
        val id = frame.get("id")?.asString.orEmpty()
        if (id.isEmpty()) return
        if (!markSeen(id)) return
        persistAndNotify(frame)
        ackReceived(socket, id)
    }

    private fun handleQueuedBatch(socket: WebSocket, frame: JsonObject) {
        val arr = frame.get("messages")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        for (one in arr) {
            if (one == null || one.isJsonNull || !one.isJsonObject) continue
            val item = one.asJsonObject
            val id = item.get("id")?.asString.orEmpty()
            if (id.isEmpty()) continue
            if (!markSeen(id)) continue
            persistAndNotify(item)
            ackReceived(socket, id)
        }
    }

    /**
     * server 应答 message_create. accepted/skipped/replaced 都视作"server 已落库",
     * 客户端把对应 turn 标 synced=1, drainer 跳过. rejected 留给 drainer 兜底重试.
     */
    private fun handleMessagePersisted(frame: JsonObject) {
        val ok = frame.get("ok")?.asBoolean ?: false
        val id = frame.get("id")?.asString.orEmpty()
        val status = frame.get("status")?.asString.orEmpty()
        if (id.isEmpty()) return
        if (ok && (status == "accepted" || status == "skipped" || status == "replaced")) {
            val ctx = appContext ?: return
            try {
                AppDatabase.getInstance(ctx).messageDao().markSynced(listOf(id))
            } catch (e: Exception) {
                Log.w(TAG, "markSynced failed for $id", e)
            }
        } else {
            val reason = frame.get("reason")?.asString ?: "rejected"
            Log.w(TAG, "message_create rejected id=$id reason=$reason")
        }
    }

    private fun handleMessageUpdated(frame: JsonObject) {
        val ok = frame.get("ok")?.asBoolean ?: false
        val id = frame.get("id")?.asString.orEmpty()
        if (!ok) {
            val err = frame.get("error")?.asString ?: "?"
            Log.w(TAG, "message_update failed id=$id error=$err")
        }
    }

    private fun ackReceived(socket: WebSocket, id: String) {
        try {
            socket.send(JsonObject().apply {
                addProperty("op", "ack")
                addProperty("id", id)
                addProperty("status", "received")
            }.toString())
        } catch (_: Exception) {}
    }

    /**
     * 写入本地 message + 触发通知. 仅当 assistant 是 character 类型且
     * allowProactiveMessage=true 时才入会话历史; 其它类型只丢通知不污染消息流.
     */
    private fun persistAndNotify(frame: JsonObject) {
        val ctx = appContext ?: return
        val id = frame.get("id")?.asString.orEmpty()
        val assistantId = frame.get("assistantId")?.asString.orEmpty()
        var sessionId = frame.get("sessionId")?.asString.orEmpty()
        val title = frame.get("title")?.asString.orEmpty()
        val body = frame.get("body")?.asString.orEmpty()
        val createdAt = frame.get("createdAt")?.takeIf { !it.isJsonNull }?.asLong
            ?: System.currentTimeMillis()
        if (body.isEmpty()) return

        val assistant = if (assistantId.isNotEmpty()) MyAssistantStore(ctx).getById(assistantId) else null
        val isCharacter = assistant != null && "character" == assistant.type && assistant.allowProactiveMessage

        if (isCharacter) {
            if (sessionId.isEmpty()) {
                sessionId = SessionAssistantBindingStore(ctx)
                    .getSessionIdsByAssistantId(assistantId)
                    .lastOrNull() ?: ""
            }
            if (sessionId.isNotEmpty()) {
                try {
                    val msg = Message(sessionId, Message.ROLE_ASSISTANT, body)
                    msg.assistantId = assistantId
                    msg.proactiveKind = 2
                    msg.createdAt = createdAt
                    AppDatabase.getInstance(ctx).messageDao().insert(msg)
                } catch (e: Exception) {
                    Log.w(TAG, "persist proactive failed", e)
                }
            } else {
                Log.d(TAG, "no sessionId for assistant=$assistantId, skipping persist")
            }
        }

        try {
            val notifTitle = if (title.isNotEmpty()) title else assistant?.name ?: "新消息"
            ProactiveMessageNotifier(ctx).notifyMessage(
                messageId = id,
                title = notifTitle,
                body = body,
                sessionId = sessionId.ifEmpty { null },
                assistantId = assistantId.ifEmpty { null },
            )
        } catch (e: Exception) {
            Log.w(TAG, "notify failed", e)
        }
    }
}
