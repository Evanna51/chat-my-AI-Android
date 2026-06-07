package com.example.aichat.inkos

import android.os.Handler
import android.os.Looper
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * inkOS Studio 的 SSE (`/api/v1/events`) 客户端。
 *
 * 进程级单例:多个 Activity/Service 可以同时挂监听,只维持一条长连接。
 * 断线自动重连(指数退避,最大 30s),`stop()` 显式终止。
 *
 * 事件按 inkos server.js `broadcast(event, data)` 来,事件名见 `book:creating`、
 * `book:created`、`book:error`、`write:start`、`draft:delta` 等几十个。所有 data
 * 都是 JSON object,这里统一解析后通过 [Listener] 回调到主线程。
 *
 * **不持久化**: 监听器在进程消失/Listener 被移除时丢消息。建书完成这类一次性
 * 通知如果想跨进程不丢,需要走 wi-chat-server 中转 + 系统通知。当前 MVP 只在
 * 应用前台时响应。
 */
object InkosEventBus {

    interface Listener {
        fun onEvent(event: String, data: JsonObject)
        fun onError(t: Throwable) {}
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    /**
     * SSE 是长连接, OkHttp 默认 readTimeout 10s 会把它咔掉。
     * 设 0 = 不超时,只靠 server 端 30s 一次 ping 保活,加上下面的 try/catch 重连。
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "InkosEventBus").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var currentCall: Call? = null

    @Synchronized
    fun addListener(l: Listener) {
        listeners.add(l)
        if (!running) {
            running = true
            executor.execute { connectLoop() }
        }
    }

    @Synchronized
    fun removeListener(l: Listener) {
        listeners.remove(l)
        // 监听器空了之后不主动断 — 后续可能很快再有人来,断了又建反而抖。
        // 想完全停掉调 [stop]。
    }

    @Synchronized
    fun stop() {
        running = false
        currentCall?.cancel()
        currentCall = null
    }

    private fun connectLoop() {
        var backoffMs = 1000L
        while (running) {
            try {
                val req = Request.Builder()
                    .url("${InkosClient.BASE_URL}/api/v1/events")
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .build()
                val call = client.newCall(req)
                currentCall = call
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val source = resp.body?.source() ?: throw IOException("Empty body")
                    backoffMs = 1000L // 成功握手就把退避归位
                    readSseFrames(source) { event, data -> dispatch(event, data) }
                }
            } catch (t: Throwable) {
                if (!running) break
                notifyError(t)
                Thread.sleep(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    /**
     * SSE 帧格式: 多行 `field: value` 块,空行触发派发。
     * 这里只处理 `event:` 和 `data:`,其它 (id, retry, 注释 `:`) 忽略。
     * `data:` 多行会被拼成一段 (newline 分隔),inkos 实际只发一行。
     */
    private inline fun readSseFrames(
        source: okio.BufferedSource,
        onFrame: (event: String, dataStr: String) -> Unit,
    ) {
        var event = "message"
        val data = StringBuilder()
        while (running) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty()) {
                if (data.isNotEmpty()) onFrame(event, data.toString())
                event = "message"
                data.setLength(0)
                continue
            }
            when {
                line.startsWith(":") -> Unit // 注释行 / 心跳
                line.startsWith("event:") -> event = line.substring(6).trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    // 标准: 去掉 "data:" 后的单个前导空格
                    val v = line.substring(5)
                    data.append(if (v.startsWith(" ")) v.substring(1) else v)
                }
                // id: / retry: 忽略
            }
        }
    }

    private fun dispatch(event: String, dataStr: String) {
        // ping 事件 data 是空串, JsonParser 会抛 — 直接跳过
        if (dataStr.isEmpty() || event == "ping") return
        val obj = runCatching { JsonParser().parse(dataStr).asJsonObject }.getOrNull() ?: return
        if (listeners.isEmpty()) return
        mainHandler.post {
            for (l in listeners) {
                runCatching { l.onEvent(event, obj) }
            }
        }
    }

    private fun notifyError(t: Throwable) {
        if (listeners.isEmpty()) return
        mainHandler.post {
            for (l in listeners) {
                runCatching { l.onError(t) }
            }
        }
    }
}
