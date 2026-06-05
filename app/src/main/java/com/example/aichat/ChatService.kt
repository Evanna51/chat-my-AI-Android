package com.example.aichat

import android.content.Context
import android.util.Log
import com.example.aichat.chat.ChatJsonHelpers.firstNonEmpty
import com.example.aichat.chat.ChatJsonHelpers.getInt
import com.example.aichat.chat.ChatJsonHelpers.getString
import com.example.aichat.chat.ChatJsonHelpers.getStringFlexible
import com.example.aichat.chat.ChatReasoningExtractor
import com.example.aichat.chat.ChatTextHelpers
import com.example.aichat.writer.WriterJsonHelpers
import com.example.aichat.chat.ChatToolCallAccumulator
import com.example.aichat.chat.InlineThinkProcessor
import com.example.aichat.chat.InlineThinkState
import com.example.aichat.chat.ToolCallBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 聊天服务，从 AiModelConfig 读取当前配置进行请求。
 */
class ChatService(context: Context) {

    companion object {
        private const val TAG = "ChatService"
        /** Maximum chained tool-call rounds before giving up. Prevents loops. */
        private const val TOOL_LOOP_MAX_ROUNDS = 3
    }

    internal val context: Context = context.applicationContext

    private val titleGenerator by lazy { com.example.aichat.chat.ChatTitleGenerator(this) }
    private val outlineService by lazy { com.example.aichat.writer.WriterOutlineService(this) }
    private val chapterPlanService by lazy { com.example.aichat.writer.WriterChapterPlanService(this) }
    private val volumeService by lazy { com.example.aichat.writer.WriterVolumeService(this) }

    /**
     * 子任务（话题命名 / 大纲生成 / 总结 / 章节计划 / 知情边界）走这条：
     * 用户在「编辑模型」里设的默认参数会覆盖调用方写死的 hardcoded 值。
     * 没设就保持调用方原值。
     */
    internal fun applyModelDefaultsToRequest(
        request: ChatApi.ChatRequest,
        providerId: String?,
        modelId: String?,
    ) {
        val params = lookupModelDefaultParams(providerId, modelId) ?: return
        params.temperature?.let { request.temperature = it.toDouble() }
        params.topP?.let { request.topP = it.toDouble() }
        params.maxTokens?.let { request.maxTokens = it }
        params.frequencyPenalty?.let { request.frequencyPenalty = it.toDouble() }
        params.presencePenalty?.let { request.presencePenalty = it.toDouble() }
        params.topK?.let { request.topK = it }
    }

    internal fun lookupModelDefaultParams(providerId: String?, modelId: String?): ModelDefaultParams? {
        if (providerId.isNullOrEmpty() || modelId.isNullOrEmpty()) return null
        val provider = ProviderManager(context).getProvider(providerId) ?: return null
        val model = provider.models.firstOrNull { it.modelId == modelId } ?: return null
        return model.defaultParams
    }

    /**
     * 主对话路径走这条：会话已经显式写了哪些字段就保留，没写（null）的字段才回退到模型默认。
     * temperature/topP 在 SessionChatOptions 里是 primitive 一定有值，所以不在这里覆盖。
     */
    internal fun applyModelDefaultsToRequestForNullFields(
        request: ChatApi.ChatRequest,
        providerId: String?,
        modelId: String?,
    ) {
        val params = lookupModelDefaultParams(providerId, modelId) ?: return
        if (request.maxTokens == null) request.maxTokens = params.maxTokens
        if (request.frequencyPenalty == null) request.frequencyPenalty = params.frequencyPenalty?.toDouble()
        if (request.presencePenalty == null) request.presencePenalty = params.presencePenalty?.toDouble()
        if (request.topK == null) request.topK = params.topK
    }

    interface ChatHandle {
        fun cancel()
        fun isCancelled(): Boolean
    }

    internal class ChatHandleImpl : ChatHandle {
        @Volatile var cancelled: Boolean = false
        @Volatile var cancelledCallbackFired: Boolean = false
        @Volatile var retrofitCall: retrofit2.Call<*>? = null
        @Volatile var okHttpCall: okhttp3.Call? = null

        override fun cancel() {
            cancelled = true
            val callA = retrofitCall
            if (callA != null) {
                try { callA.cancel() } catch (ignored: Exception) {}
            }
            val callB = okHttpCall
            if (callB != null) {
                try { callB.cancel() } catch (ignored: Exception) {}
            }
        }

        override fun isCancelled(): Boolean = cancelled

        fun bindRetrofitCall(call: retrofit2.Call<*>?) {
            this.retrofitCall = call
            if (cancelled && call != null) {
                try { call.cancel() } catch (ignored: Exception) {}
            }
        }

        fun bindOkHttpCall(call: okhttp3.Call?) {
            this.okHttpCall = call
            if (cancelled && call != null) {
                try { call.cancel() } catch (ignored: Exception) {}
            }
        }

        fun tryFireCancelled(): Boolean {
            if (cancelledCallbackFired) return false
            cancelledCallbackFired = true
            return true
        }
    }

    @JvmOverloads
    fun chat(
        history: List<Message>,
        userMessage: String,
        options: SessionChatOptions? = null,
        callback: ChatCallback,
        toolBridge: com.example.aichat.sync.ToolBridge? = null,
    ): ChatHandle {
        val handle = ChatHandleImpl()
        val config: AiModelConfig.ResolvedConfig
        try {
            config = AiModelConfig(context).getConfigForChat()
        } catch (e: Exception) {
            callback.onError(context.getString(R.string.error_config_parse_failed, e.message ?: ""))
            return handle
        }
        if (config == null || !config.isValid()) {
            callback.onError(context.getString(R.string.error_no_chat_model_selected))
            return handle
        }

        var using = options ?: SessionChatOptions()
        var selectedProviderId = ""
        if (using.modelKey != null && using.modelKey.contains(":")) {
            try {
                val selected = ConfiguredModelPicker.Option.fromStorageKey(using.modelKey, context)
                if (selected != null) {
                    val selProviderId = selected.providerId
                    selectedProviderId = selProviderId ?: ""
                    val p = ProviderManager(context).getProvider(selProviderId ?: "")
                    if (p != null) {
                        config.apiHost = p.apiHost
                        config.apiPath = p.apiPath
                        config.apiKey = p.apiKey
                    }
                    val selModelId = selected.modelId
                    if (selModelId != null && selModelId.isNotEmpty()) {
                        config.modelId = selModelId
                    }
                    // Thinking is a model capability: override from per-model config.
                    if (!using.thinking && p != null && !selModelId.isNullOrEmpty()) {
                        val modelInfo = p.models.firstOrNull { it.modelId == selModelId }
                        if (modelInfo?.thinkingEnabled == true) {
                            using = using.copy(thinking = true)
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }

        selectedProviderId = resolveProviderId(selectedProviderId, config.apiHost)
        var baseUrl = config.toRetrofitBaseUrl()
        if (baseUrl == null || baseUrl.isEmpty()) {
            callback.onError(context.getString(R.string.error_api_url_invalid))
            return handle
        }

        val logging = HttpLoggingInterceptor()
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC)
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        if (!baseUrl.endsWith("/")) baseUrl += "/"

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val api = retrofit.create(ChatApi::class.java)

        val messages = buildMessages(history, userMessage, using, selectedProviderId, config.apiHost)
        if (messages.isEmpty()) {
            callback.onError("消息内容为空")
            return handle
        }

        if (using.streamOutput) {
            streamChat(client, config, using, messages, callback, selectedProviderId, handle,
                toolBridge = toolBridge, toolRound = 0)
            return handle
        }

        val requestMessages = ArrayList(messages)
        val request = ChatApi.ChatRequest()
        request.model = config.modelId
        request.messages = requestMessages
        request.stream = false
        request.temperature = using.temperature.toString().toDouble()
        request.topP = using.topP.toString().toDouble()
        // 会话级新参数：用户填了就用，留空时走模型默认 fallback。
        request.maxTokens = using.maxTokens
        request.frequencyPenalty = using.frequencyPenalty?.toDouble()
        request.presencePenalty = using.presencePenalty?.toDouble()
        request.topK = using.topK
        applyModelDefaultsToRequestForNullFields(request, selectedProviderId, config.modelId)
        request.stop = parseStopSequences(using.stop)
        request.thinking = null
        request.reasoning = ProviderRequestOptionsBuilder.buildReasoningConfig(selectedProviderId, using)
        request.providerOptions = ProviderRequestOptionsBuilder.buildProviderOptions(selectedProviderId, using)
        Log.d(TAG, "chat request providerId=$selectedProviderId"
                + ", model=${config.modelId}"
                + ", thinking=${using.thinking}"
                + ", stopCount=${request.stop?.size ?: 0}"
                + ", reasoning=${request.reasoning?.toString() ?: "null"}"
                + ", providerOptions=${request.providerOptions?.toString() ?: "null"}")

        val auth = if (config.apiKey != null && config.apiKey.trim().isNotEmpty())
            "Bearer " + config.apiKey.trim() else null
        val chatUrl = ApiUtils.toBaseUrl(config.apiHost, config.apiPath)

        val start = System.currentTimeMillis()
        val call = api.chatWithUrl(chatUrl, auth, "application/json", request)
        handle.bindRetrofitCall(call)
        call.enqueue(object : retrofit2.Callback<ChatApi.ChatResponse> {
            override fun onResponse(
                call: retrofit2.Call<ChatApi.ChatResponse>,
                response: retrofit2.Response<ChatApi.ChatResponse>
            ) {
                if (handle.isCancelled()) {
                    fireCancelledOnce(callback, handle)
                    return
                }
                try {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        val choices = body.choices
                        if (choices != null && choices.isNotEmpty()) {
                            val choice = choices[0]
                            if (choice != null && choice.message != null) {
                                val content = ChatTextHelpers.extractAssistantContent(body)
                                callback.onUsage(0, 0, 0, System.currentTimeMillis() - start)
                                callback.onSuccess(content ?: "")
                                return
                            }
                        }
                    }
                    var detail = ""
                    try {
                        if (response.errorBody() != null) {
                            detail = response.errorBody()!!.string()
                        }
                    } catch (ignored: Exception) {}
                    callback.onError("请求失败: " + response.code()
                            + "\nURL: " + chatUrl
                            + if (detail.isEmpty()) "" else ("\n" + detail))
                } catch (e: Exception) {
                    callback.onError(context.getString(R.string.error_parse_response_failed, e.message ?: ""))
                }
            }

            override fun onFailure(call: retrofit2.Call<ChatApi.ChatResponse>, t: Throwable) {
                if (handle.isCancelled() || call.isCanceled) {
                    fireCancelledOnce(callback, handle)
                    return
                }
                callback.onError(t.message ?: "未知错误")
            }
        })
        return handle
    }

    fun generateThreadTitle(firstUserMessage: String?, callback: ChatCallback) {
        titleGenerator.generate(firstUserMessage, callback)
    }

    fun generateSessionOutline(history: List<Message>?, outlinePrompt: String? = null, callback: ChatCallback) {
        outlineService.generateSession(history, outlinePrompt, callback)
    }
    fun summarizeMessageForOutline(content: String?, outlinePrompt: String? = null, callback: ChatCallback) {
        outlineService.summarize(content, outlinePrompt, callback)
    }
    fun generateChapterPlanJson(ctx: ChapterPlanContext, callback: ChatCallback) {
        chapterPlanService.generate(ctx, callback)
    }

    /**
     * 基于一段章节计划（含人物/世界/知情等上下文）生成一篇卷大纲。
     * 输出纯文本（不强 JSON），方便用户编辑、AI 复读时阅读。
     *
     * @param volumeTitle 卷标题，用于 prompt 中明确目标范围
     * @param coverageRange 形如 “章节1 ~ 章节10”
     * @param promptContext OutlinePromptBuilder.buildFull 输出的上下文
     */
    fun generateVolumeOutline(
        volumeTitle: String,
        coverageRange: String,
        promptContext: String,
        callback: ChatCallback,
    ) {
        volumeService.generateVolume(volumeTitle, coverageRange, promptContext, callback)
    }

    /**
     * 从已有大纲（章节计划 + 人物 + 世界）提取每章的知情约束。
     * 输入是结构化大纲文本（由 OutlinePromptBuilder 构造）。
     * 输出 JSON 数组，每条带 chapter 字段标明所属章节（”通用”=跨章节）。
     */
    fun extractKnowledgeConstraints(outlineText: String?, callback: ChatCallback) {
        volumeService.extractKnowledge(outlineText, callback)
    }

    internal fun buildMessages(
        history: List<Message>?,
        userMessage: String?,
        using: SessionChatOptions,
        providerId: String,
        apiHost: String?,
    ): List<ChatApi.ChatMessage> {
        val messages = ArrayList<ChatApi.ChatMessage>()
        if (using.systemPrompt != null && using.systemPrompt.trim().isNotEmpty()) {
            val sys = using.systemPrompt.trim()
            messages.add(ChatApi.ChatMessage("system", sys))
            // 自动对话调试: 看 system prompt 末尾是不是真的带 <output_protocol> 块.
            // 没带就说明 ChatViewModel 那边 options.systemPrompt 被中途覆盖了.
            val hasOutputProtocol = sys.contains("<output_protocol>")
            val tail = sys.takeLast(1500).replace("\n", "\\n")
            Log.d(TAG, "SYSTEM len=${sys.length} hasOutputProtocol=$hasOutputProtocol tail1500=$tail")
        }
        val source = history ?: ArrayList()
        val limit = using.contextMessageCount
        var start = 0
        if (limit >= 0 && source.size > limit) start = source.size - limit

        // Pass 1 (always): pull canonical user/assistant turns from history.
        // - 过滤 tool_call (3) / tool_result (4): 它们由别的链路 (precomputedMessagesJson)
        //   处理, 通用路径不应作为空 assistant 发出去
        // - 过滤 empty content 行: 同上, 防止干扰模板
        // - System (2) 也忽略, system prompt 已经 prepend
        val raw = ArrayList<ChatApi.ChatMessage>()
        for (i in start until source.size) {
            val m = source[i] ?: continue
            if (m.role != Message.ROLE_USER && m.role != Message.ROLE_ASSISTANT) continue
            val content = (m.content ?: "")
            if (content.isEmpty()) continue
            val role = if (m.role == Message.ROLE_USER) "user" else "assistant"
            raw.add(ChatApi.ChatMessage(role, content))
        }

        // 严格交替模式: 只对本地小模型 (Qwen / llama.cpp / Ollama / LM Studio 等) 启用.
        // 它们的 jinja 模板要求 user-first + 严格 user/assistant 交替, 否则 raise
        // "No user query found in messages.". 云端模型 (OpenAI/Claude/Gemini/...) 都
        // 接受连续同 role / assistant 起头的 history, 不需要这层"破坏性"处理.
        val strictAlternation = isStrictAlternationProvider(providerId, apiHost)

        // Pass 2 (conditional): 严格交替模式下合并连续同 role 用 \n\n. 自动对话 split /
        // follow-up 会产生连续 assistant 行, 否则模板崩.
        // 非严格模式: 保留原始行结构, 让模型自己看到"刚才我分了 3 条说话".
        var lastRole = ""
        for (one in raw) {
            if (strictAlternation && one.role == lastRole && messages.isNotEmpty()) {
                val tail = messages[messages.size - 1]
                tail.content = (tail.content ?: "") + "\n\n" + (one.content ?: "")
            } else {
                messages.add(ChatApi.ChatMessage(one.role, one.content ?: ""))
                lastRole = one.role
            }
        }
        // 最后追加本轮 user 消息 (调用方传入的实际 prompt / follow-up instruction).
        // 严格模式下: 若上一条已是 user 则合并 (避免 user/user 连排); 非严格: 独立 append.
        val finalUser = userMessage ?: ""
        if (strictAlternation && "user" == lastRole && messages.isNotEmpty()) {
            val tail = messages[messages.size - 1]
            tail.content = (tail.content ?: "") + "\n\n" + finalUser
        } else {
            messages.add(ChatApi.ChatMessage("user", finalUser))
        }

        // Pass 3 (conditional): 严格模式下, system 之后第一条必须是 user, 否则 Qwen 模板
        // 会 raise "No user query found in messages.". 把领头的 assistant 行全部删掉.
        // 非严格模式: 保留 history 原貌, 云端模型不在意.
        if (strictAlternation) {
            var systemEnd = 0
            while (systemEnd < messages.size && messages[systemEnd].role == "system") systemEnd++
            while (systemEnd < messages.size && messages[systemEnd].role != "user") {
                messages.removeAt(systemEnd)
            }
        }
        return messages
    }

    /**
     * 判断目标 provider 是否需要严格 user-first / 交替 / 一致 role 的对话窗口.
     *
     * 命中: LM Studio / Ollama / llama.cpp / koboldcpp 这类用 Hugging Face jinja
     * 模板原样跑的本地引擎; 它们的模板逻辑不容忍 self-talk (连续 assistant) 或
     * assistant-first 起头.
     *
     * 不命中 (默认): OpenAI / Anthropic / Gemini / DeepSeek / OpenRouter 等
     * managed cloud API, 它们都很宽松, 我们应当尽量保留原始 history 结构, 让模型
     * 看到"AI 之前分了几条说话"的真实节奏 (尤其是自动对话 split 的语境).
     *
     * 启发式 (按顺序):
     *   1. providerId 名字含 lmstudio / ollama / llama / koboldcpp
     *   2. apiHost 是 localhost / 127.0.0.1 / RFC1918 内网 / *.local
     */
    internal fun isStrictAlternationProvider(providerId: String?, apiHost: String?): Boolean {
        val pid = (providerId ?: "").lowercase(java.util.Locale.ROOT)
        if (pid.isNotEmpty()) {
            for (hint in arrayOf("lmstudio", "lm-studio", "lm_studio", "ollama", "llama.cpp", "llamacpp", "koboldcpp", "kobold")) {
                if (pid.contains(hint)) return true
            }
        }
        val h = (apiHost ?: "").lowercase(java.util.Locale.ROOT)
        if (h.isEmpty()) return false
        if (h.contains("localhost") || h.contains("127.0.0.1")) return true
        if (h.contains(".local")) return true
        if (h.contains("192.168.")) return true
        if (h.contains("//10.") || h.contains(":10.")) return true
        for (i in 16..31) if (h.contains("172.$i.")) return true
        return false
    }

    internal fun streamChat(
        client: OkHttpClient,
        config: AiModelConfig.ResolvedConfig,
        using: SessionChatOptions,
        messages: List<ChatApi.ChatMessage>,
        callback: ChatCallback,
        providerId: String,
        handle: ChatHandleImpl,
        toolBridge: com.example.aichat.sync.ToolBridge? = null,
        toolRound: Int = 0,
        precomputedMessagesJson: JsonArray? = null,
    ) {
        val chatUrl = ApiUtils.toBaseUrl(config.apiHost, config.apiPath)
        val request = JsonObject()
        request.addProperty("model", config.modelId)
        request.addProperty("stream", true)
        request.addProperty("temperature", using.temperature)
        request.addProperty("top_p", using.topP)
        // 新参数：会话填了直接用，否则查模型默认；都没就不传。
        val streamModelDefaults = lookupModelDefaultParams(providerId, config.modelId)
        (using.maxTokens ?: streamModelDefaults?.maxTokens)?.let { request.addProperty("max_tokens", it) }
        (using.frequencyPenalty ?: streamModelDefaults?.frequencyPenalty)?.let { request.addProperty("frequency_penalty", it) }
        (using.presencePenalty ?: streamModelDefaults?.presencePenalty)?.let { request.addProperty("presence_penalty", it) }
        (using.topK ?: streamModelDefaults?.topK)?.let { request.addProperty("top_k", it) }
        val arr = precomputedMessagesJson ?: JsonArray().also { acc ->
            for (m in messages) {
                val one = JsonObject()
                one.addProperty("role", m.role)
                one.addProperty("content", m.content)
                acc.add(one)
            }
        }
        request.add("messages", arr)
        if (toolBridge != null && toolBridge.isReady() && toolRound < TOOL_LOOP_MAX_ROUNDS) {
            val toolsArr = toolBridge.toolsJson()
            request.add("tools", toolsArr)
            request.addProperty("tool_choice", "auto")
            Log.d(TAG, "tools injected: count=${toolsArr.size()} round=$toolRound")
        } else {
            Log.d(TAG, "tools NOT injected:"
                + " bridge=${if (toolBridge == null) "null" else "non-null"}"
                + " ready=${toolBridge?.isReady()}"
                + " round=$toolRound/$TOOL_LOOP_MAX_ROUNDS")
        }
        val stops = parseStopSequences(using.stop)
        if (stops != null && stops.isNotEmpty()) {
            val stopArr = JsonArray()
            for (s in stops) stopArr.add(s)
            request.add("stop", stopArr)
        }
        val reasoning = ProviderRequestOptionsBuilder.buildReasoningConfig(providerId, using)
        if (reasoning != null) request.add("reasoning", reasoning)
        val providerOptions = ProviderRequestOptionsBuilder.buildProviderOptions(providerId, using)
        if (providerOptions != null) request.add("providerOptions", providerOptions)
        Log.d(TAG, "stream request providerId=$providerId"
                + ", model=${config.modelId}"
                + ", thinking=${using.thinking}"
                + ", stopCount=${stops?.size ?: 0}"
                + ", reasoning=${reasoning?.toString() ?: "null"}"
                + ", providerOptions=${providerOptions?.toString() ?: "null"}")

        val rb = Request.Builder()
            .url(chatUrl)
            .addHeader("Accept", "text/event-stream")
            .addHeader("Content-Type", "application/json")
            .post(request.toString().toRequestBody("application/json".toMediaType()))
        if (config.apiKey != null && config.apiKey.trim().isNotEmpty()) {
            rb.addHeader("Authorization", "Bearer " + config.apiKey.trim())
        }
        val okRequest = rb.build()

        val start = System.currentTimeMillis()
        val call = client.newCall(okRequest)
        handle.bindOkHttpCall(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (handle.isCancelled() || call.isCanceled()) {
                    fireCancelledOnce(callback, handle)
                    return
                }
                callback.onError(e.message ?: "流式请求失败")
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                if (handle.isCancelled()) {
                    fireCancelledOnce(callback, handle)
                    return
                }
                if (!response.isSuccessful) {
                    var detail = ""
                    try {
                        response.body.use { body ->
                            if (body != null) detail = body.string()
                        }
                    } catch (ignored: Exception) {}
                    callback.onError("请求失败: " + response.code
                            + "\nURL: " + chatUrl
                            + if (detail.isEmpty()) "" else ("\n" + detail))
                    return
                }
                val fullContent = StringBuilder()
                val fullReasoning = StringBuilder()
                val inlineThinkState = InlineThinkState()
                val normalizeInlineThink = InlineThinkProcessor.shouldNormalize(providerId)
                // V8 (2026-05-10): autoChat 双协议. 段间 `\n\n` 自然分隔 (流式直接显示);
                // 末尾 `||==FOLLOWUP/STOP/SKIP==||` 元信息标记由 splitFilter 流式吞掉.
                // fullContent 累加完整 raw, onSuccess 时 ProactiveMetaParser 切分 + 提元信息.
                val splitFilter = if (using.autoChatEnabled)
                    com.example.aichat.chat.ProactiveSplitStreamFilter() else null
                var promptTokens = 0
                var completionTokens = 0
                var totalTokens = 0
                val toolCallsByIndex = LinkedHashMap<Int, ToolCallBuilder>()
                var sawToolCallFinish = false
                try {
                    response.body.use { body ->
                        if (body == null) {
                            callback.onError("流式响应为空")
                            return
                        }
                        val source = body.source()
                        while (!source.exhausted()) {
                            if (handle.isCancelled()) {
                                fireCancelledOnce(callback, handle)
                                return
                            }
                            val line = source.readUtf8Line() ?: break
                            val trimmed = line.trim()
                            val payload: String
                            if (trimmed.startsWith("data:")) {
                                payload = trimmed.substring(5).trim()
                            } else if (trimmed.startsWith("{")) {
                                payload = trimmed
                            } else {
                                continue
                            }
                            if (payload.isEmpty()) continue
                            if ("[DONE]" == payload) break
                            try {
                                val obj = JsonParser().parse(payload).asJsonObject
                                val usage = if (obj.has("usage") && obj.get("usage").isJsonObject)
                                    obj.getAsJsonObject("usage") else null
                                if (usage != null) {
                                    promptTokens = getInt(usage, "prompt_tokens")
                                    completionTokens = getInt(usage, "completion_tokens")
                                    totalTokens = getInt(usage, "total_tokens")
                                }
                                val choices = if (obj.has("choices") && obj.get("choices").isJsonArray)
                                    obj.getAsJsonArray("choices") else null
                                if (choices == null || choices.size() == 0) continue
                                val first = if (choices[0].isJsonObject) choices[0].asJsonObject else null
                                if (first == null) continue
                                val delta = when {
                                    first.has("delta") && first.get("delta").isJsonObject ->
                                        first.getAsJsonObject("delta")
                                    first.has("message") && first.get("message").isJsonObject ->
                                        first.getAsJsonObject("message")
                                    else -> null
                                }
                                if (delta == null) continue
                                val contentDelta = getString(delta, "content")
                                var emittedInlineReasoning = false
                                if (contentDelta.isNotEmpty()) {
                                    if (normalizeInlineThink) {
                                        val parts = InlineThinkProcessor.splitInlineThink(contentDelta, inlineThinkState, false)
                                        if (parts.content.isNotEmpty()) {
                                            fullContent.append(parts.content)
                                            val visible = splitFilter?.process(parts.content) ?: parts.content
                                            if (visible.isNotEmpty()) callback.onPartial(visible)
                                        }
                                        if (parts.reasoning.isNotEmpty()) {
                                            emittedInlineReasoning = true
                                            fullReasoning.append(parts.reasoning)
                                            callback.onReasoning(fullReasoning.toString())
                                        }
                                    } else {
                                        fullContent.append(contentDelta)
                                        val visible = splitFilter?.process(contentDelta) ?: contentDelta
                                        if (visible.isNotEmpty()) callback.onPartial(visible)
                                    }
                                }
                                val reasoningDelta = ChatReasoningExtractor.extract(obj, first, delta)
                                if (reasoningDelta.isNotEmpty() && !emittedInlineReasoning) {
                                    fullReasoning.append(reasoningDelta)
                                    callback.onReasoning(fullReasoning.toString())
                                }
                                if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray) {
                                    ChatToolCallAccumulator.accumulateDelta(delta.getAsJsonArray("tool_calls"), toolCallsByIndex)
                                }
                                val finishReason = getString(first, "finish_reason")
                                // OpenAI/DeepSeek → "tool_calls"; Anthropic → "tool_use"
                                if (finishReason == "tool_calls" || finishReason == "tool_use") sawToolCallFinish = true
                            } catch (ignored: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    if (handle.isCancelled() || call.isCanceled()) {
                        fireCancelledOnce(callback, handle)
                        return
                    }
                    callback.onError("流式解析失败: " + (e.message ?: ""))
                    return
                }
                if (normalizeInlineThink) {
                    val tail = InlineThinkProcessor.splitInlineThink("", inlineThinkState, true)
                    if (tail.content.isNotEmpty()) {
                        fullContent.append(tail.content)
                        val visible = splitFilter?.process(tail.content) ?: tail.content
                        if (visible.isNotEmpty()) callback.onPartial(visible)
                    }
                    if (tail.reasoning.isNotEmpty()) {
                        fullReasoning.append(tail.reasoning)
                        callback.onReasoning(fullReasoning.toString())
                    }
                }
                // V8: 把 split filter 缓冲的 tail (没遇到 ||== 时可能保留 3 字符) flush.
                splitFilter?.flushTail()?.let { tail ->
                    if (tail.isNotEmpty()) callback.onPartial(tail)
                }
                if (handle.isCancelled()) {
                    fireCancelledOnce(callback, handle)
                    return
                }
                // Tool-call branch: model wants to invoke client tools. Run them
                // server-side, append tool results to the message log, recurse
                // into a fresh streamChat round (round-budgeted).
                if ((sawToolCallFinish || toolCallsByIndex.isNotEmpty())
                    && toolBridge != null && toolBridge.isReady()
                    && toolRound < TOOL_LOOP_MAX_ROUNDS
                ) {
                    val toolCalls = toolCallsByIndex.values.toList()
                    if (toolCalls.isNotEmpty()) {
                        // 1. append assistant turn carrying tool_calls (LLM-bound JSON arr).
                        val assistantToolCallMsg = ChatToolCallAccumulator.buildAssistantToolCallMessage(toolCalls)
                        arr.add(assistantToolCallMsg)
                        // 1b. emit a persistable record for the same row so the consumer
                        //     (ViewModel) can write it to the local message log.
                        try {
                            val callsArrJson = if (assistantToolCallMsg.has("tool_calls"))
                                assistantToolCallMsg.getAsJsonArray("tool_calls").toString() else "[]"
                            callback.onToolMessageRecorded(
                                ToolMessageRecord(
                                    role = Message.ROLE_TOOL_CALL,
                                    content = "",
                                    toolCallsJson = callsArrJson,
                                    toolCallId = "",
                                    toolName = "",
                                    createdAt = System.currentTimeMillis(),
                                )
                            )
                        } catch (_: Exception) {}
                        // 2. invoke each tool, append role=tool message per call.
                        for (tc in toolCalls) {
                            if (handle.isCancelled()) {
                                fireCancelledOnce(callback, handle)
                                return
                            }
                            try {
                                callback.onToolCallStart(tc.name)
                            } catch (_: Exception) {}
                            val rawResult = toolBridge.invoke(tc.name, tc.argumentsBuilder.toString())
                            // tc.id 此时已被 buildAssistantToolCallMessage 写回过 (fallback 同源),
                            // 所以这里直接用就跟 assistant.tool_calls[i].id 对得上.
                            val resolvedCallId = tc.id
                            // 喂给 LLM 的版本: search_memory 转成结构化纯文本, 别的 tool 直传 raw.
                            //   背景: 模型 (尤其是 DeepSeek V3.2 这种) 把嵌套 JSON 当 tool result 读时
                            //   常抓不到 memories[].content, 转纯文本后命中率显著提升.
                            // ToolCallLog (audit log) 仍然存 raw 用于调试.
                            val llmResult = if (tc.name == com.example.aichat.sync.ToolBridge.TOOL_SEARCH_MEMORY) {
                                com.example.aichat.sync.SearchMemoryFormatter.format(rawResult)
                            } else rawResult
                            arr.add(ChatToolCallAccumulator.buildToolResultMessage(resolvedCallId, tc.name, llmResult))
                            // 2b. persistable record for this tool result.
                            try {
                                callback.onToolMessageRecorded(
                                    ToolMessageRecord(
                                        role = Message.ROLE_TOOL_RESULT,
                                        content = rawResult,
                                        toolCallsJson = "",
                                        toolCallId = resolvedCallId,
                                        toolName = tc.name,
                                        createdAt = System.currentTimeMillis(),
                                    )
                                )
                            } catch (_: Exception) {}
                        }
                        // 3. recurse with the extended message log; same handle so
                        // cancellation still works through to the new request
                        streamChat(client, config, using, messages, callback,
                            providerId, handle,
                            toolBridge = toolBridge,
                            toolRound = toolRound + 1,
                            precomputedMessagesJson = arr)
                        return
                    }
                }
                callback.onUsage(promptTokens, completionTokens, totalTokens, System.currentTimeMillis() - start)
                // V8: parser 提元信息 + 按 \n\n 切. autoChat 关闭时直接返回 raw.trim().
                // cleanContent 空 + 没 split + autoChat 启用 (排除 SKIP/STOP-only 元信息) =
                // 输出无效, 走 onError 防 UI 卡空消息.
                val rawFinal = fullContent.toString()
                if (using.autoChatEnabled) {
                    val metaExtract = com.example.aichat.chat.ProactiveMetaParser.extract(rawFinal)
                    logProactiveMetaDebug(rawFinal, metaExtract)
                    if (metaExtract.cleanContent.isBlank()
                        && (metaExtract.meta?.split == null || metaExtract.meta.split.isEmpty())
                        && metaExtract.meta == null) {  // SKIP 路径 meta 非 null, 不当错误
                        callback.onError("自动对话输出为空 (rawLen=${rawFinal.length})")
                        return
                    }
                    callback.onProactiveMeta(metaExtract.meta)
                    callback.onSuccess(metaExtract.cleanContent)
                } else {
                    callback.onProactiveMeta(null)
                    callback.onSuccess(rawFinal.trim())
                }
            }
        })
    }

    /**
     * V7 自动对话 split-marker 诊断日志:
     *   - meta=null + cleanContent 非空 → 单段, 没 marker 也合理 (短句 / 单句长回复)
     *   - meta.split.size>=2 → 多段成功切分
     *   - cleanContent 空 → LLM 啥也没输出 (上层 onError 兜底)
     * raw 前 400 字 dump 用于人工核对 LLM 实际输出 (确认 marker 是否真的出现).
     */
    internal fun logProactiveMetaDebug(
        rawFinal: String,
        extract: com.example.aichat.chat.ProactiveMetaExtractResult,
    ) {
        val rawHead = rawFinal.take(400).replace("\n", "\\n")
        val meta = extract.meta
        if (meta == null) {
            // raw 不是合法 JSON, 或者 autoChat 关闭走普通模式 (此时 cleanContent = raw)
            Log.d(TAG, "META: parse=null (rawLen=${rawFinal.length}, head=$rawHead)")
            return
        }
        val splitSize = meta.split?.size ?: 0
        val splitPreview = meta.split?.joinToString(separator = " | ") { it.take(20) } ?: "null"
        val followUp = meta.followUp
        Log.d(TAG, "META: split.size=$splitSize parts=[$splitPreview] followUp=${followUp?.let { "afterSec=${it.afterSec} intent=${it.intent.take(30)}" } ?: "null"} autoStop=${meta.autoStop} cleanLen=${extract.cleanContent.length} | rawHead=$rawHead")
    }

    internal fun fireCancelledOnce(callback: ChatCallback?, handle: ChatHandleImpl?) {
        if (callback == null || handle == null) return
        if (!handle.tryFireCancelled()) return
        callback.onCancelled()
    }

    internal fun parseStopSequences(raw: String?): List<String>? {
        if (raw == null) return null
        val text = raw.trim()
        if (text.isEmpty()) return null
        val out = ArrayList<String>()
        val lines = text.split("\\r?\\n".toRegex())
        for (line in lines) {
            val one = line.trim()
            if (one.isNotEmpty()) out.add(one)
        }
        return if (out.isEmpty()) null else out
    }

    internal fun resolveProviderId(selectedProviderId: String?, apiHost: String?): String {
        val pid = selectedProviderId?.trim()?.lowercase(java.util.Locale.ROOT) ?: ""
        if (pid.isNotEmpty()) return pid
        val host = apiHost?.trim()?.lowercase(java.util.Locale.ROOT) ?: ""
        if (host.contains("127.0.0.1:8080") || host.contains("localhost:8080")) return "llama"
        if (host.contains("127.0.0.1:11434") || host.contains("localhost:11434")) return "ollama"
        if (host.contains("127.0.0.1:1234") || host.contains("localhost:1234")) return "lmstudio"
        if (host.contains("openai.com")) return "openai"
        if (host.contains("openrouter.ai")) return "openrouter"
        if (host.contains("googleapis.com") || host.contains("generativelanguage")) return "gemini"
        return ""
    }

    internal fun shouldShowReasoning(options: SessionChatOptions?, providerId: String?, modelId: String?): Boolean {
        // Always show reasoning when the model returns it.
        // The request-side toggle (options.thinking) controls whether we ASK
        // the model to think, not whether we display reasoning it chose to emit.
        // Models like Qwen3 emit <think> tags even when not requested; SSE
        // reasoning_content fields should never be silently discarded.
        return true
    }

    internal fun isIntrinsicReasoningModel(providerId: String?, modelId: String?): Boolean {
        val pid = providerId?.trim()?.lowercase(java.util.Locale.ROOT) ?: ""
        val mid = modelId?.trim()?.lowercase(java.util.Locale.ROOT) ?: ""
        if (mid.isEmpty()) return false
        // Model-driven reasoning families: show reasoning if returned, even when toggle is off.
        if (mid.contains("reasoner")) return true
        if (mid.contains("deepseek-r1")) return true
        if (mid.matches(Regex(".*(^|[-_/])r1([-. _/]|$).*"))) return true
        // Keep provider hint as fallback for renamed reasoner deployments.
        return "deepseek" == pid && mid.contains("r1")
    }

    internal fun isLocalOpenAiCompatibleProvider(providerId: String?): Boolean {
        val pid = providerId?.trim()?.lowercase(java.util.Locale.ROOT) ?: ""
        if ("lmstudio" == pid) return true
        if ("ollama" == pid) return true
        return isLlamaProviderId(pid)
    }

    internal fun isLlamaProviderId(pid: String?): Boolean {
        if (pid == null || pid.isEmpty()) return false
        return "llama" == pid
                || "llamacpp" == pid
                || "llama.cpp" == pid
                || "llama-cpp" == pid
    }

    internal fun buildNoThinkingReasoning(providerId: String, localOpenAiCompat: Boolean): JsonObject? {
        val options = SessionChatOptions()
        options.thinking = false
        options.streamOutput = true
        val reasoning = ProviderRequestOptionsBuilder.buildReasoningConfig(providerId, options)
        if (reasoning != null) return reasoning
        if (!localOpenAiCompat) return null
        val fallback = JsonObject()
        fallback.addProperty("budget", 0)
        fallback.addProperty("format", "hide")
        return fallback
    }

    interface ChatCallback {
        fun onSuccess(content: String)
        fun onError(message: String)
        fun onCancelled() {}
        fun onPartial(delta: String) {}
        fun onReasoning(reasoning: String) {}
        fun onUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int, elapsedMs: Long) {}
        /**
         * Fired when the model emits a tool_call and the client is about to
         * invoke it. UI can show a "calling tool" indicator. Followed by either
         * onPartial/onReasoning (next round) or onError (tool loop aborted).
         */
        fun onToolCallStart(toolName: String) {}
        /**
         * Fired once for each persistable tool round message: first the
         * assistant(tool_calls) wrapper (role=ROLE_TOOL_CALL), then one row per
         * executed tool (role=ROLE_TOOL_RESULT). Consumers should write these
         * to the local message log so chat history is a faithful audit trail
         * of every LLM round.
         *
         * Contract: rows persisted via this callback MUST NOT be pushed to
         * the remote sync server — they're a local-only audit trail.
         * (Server schema since 2026-Q1 *does* accept role=tool_call /
         * tool_result, but we intentionally keep tool rounds local to limit
         * upload volume.) Implementations should leave `assistantId` empty
         * so SyncQueueDrainer's `assistantId != ''` filter skips them.
         * `turnId` is auto-assigned a non-empty UuidV7 by Message's
         * constructor — leave it alone; future cross-end delete sync may
         * rely on it.
         */
        fun onToolMessageRecorded(record: ToolMessageRecord) {}
        /**
         * Fired once per streaming chat turn, immediately before [onSuccess], when
         * 自动对话 META 协议在模型回复尾部被识别 (或缺席). [meta] 为 null 表示模型没发或解析失败,
         * 上层应回退到普通显示. 回调和 onSuccess 在同一线程顺序触发.
         */
        fun onProactiveMeta(meta: com.example.aichat.chat.ProactiveMeta?) {}
    }

    /**
     * Snapshot of a single tool-round message ready to be persisted.
     * See [ChatCallback.onToolMessageRecorded] for the contract.
     */
    data class ToolMessageRecord(
        @JvmField val role: Int,
        @JvmField val content: String,
        @JvmField val toolCallsJson: String,
        @JvmField val toolCallId: String,
        @JvmField val toolName: String,
        @JvmField val createdAt: Long,
    )
}
