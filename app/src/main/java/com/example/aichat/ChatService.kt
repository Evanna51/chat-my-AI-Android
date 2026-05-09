package com.example.aichat

import android.content.Context
import android.util.Log
import com.example.aichat.chat.ChatJsonHelpers.firstNonEmpty
import com.example.aichat.chat.ChatJsonHelpers.getInt
import com.example.aichat.chat.ChatJsonHelpers.getString
import com.example.aichat.chat.ChatJsonHelpers.getStringFlexible
import com.example.aichat.chat.ChatReasoningExtractor
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

    private val context: Context = context.applicationContext

    /**
     * 子任务（话题命名 / 大纲生成 / 总结 / 章节计划 / 知情边界）走这条：
     * 用户在「编辑模型」里设的默认参数会覆盖调用方写死的 hardcoded 值。
     * 没设就保持调用方原值。
     */
    private fun applyModelDefaultsToRequest(
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

    private fun lookupModelDefaultParams(providerId: String?, modelId: String?): ModelDefaultParams? {
        if (providerId.isNullOrEmpty() || modelId.isNullOrEmpty()) return null
        val provider = ProviderManager(context).getProvider(providerId) ?: return null
        val model = provider.models.firstOrNull { it.modelId == modelId } ?: return null
        return model.defaultParams
    }

    /**
     * 主对话路径走这条：会话已经显式写了哪些字段就保留，没写（null）的字段才回退到模型默认。
     * temperature/topP 在 SessionChatOptions 里是 primitive 一定有值，所以不在这里覆盖。
     */
    private fun applyModelDefaultsToRequestForNullFields(
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

    private class ChatHandleImpl : ChatHandle {
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
                                val content = extractAssistantContent(body)
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
        val source = firstUserMessage?.trim() ?: ""
        if (source.isEmpty()) {
            callback.onError(context.getString(R.string.error_message_empty))
            return
        }
        val config: AiModelConfig.ResolvedConfig
        try {
            config = AiModelConfig(context).getConfigForThreadNaming()
        } catch (e: Exception) {
            callback.onError(context.getString(R.string.error_config_parse_failed, ""))
            return
        }
        if (config == null || !config.isValid()) {
            callback.onError(context.getString(R.string.error_no_naming_model_selected))
            return
        }

        var providerId = ""
        val threadNamingPreset = ModelConfig(context).getThreadNamingPreset()
        if (threadNamingPreset != null && threadNamingPreset.contains(":")) {
            providerId = threadNamingPreset.substring(0, threadNamingPreset.indexOf(':'))
        }
        providerId = resolveProviderId(providerId, config.apiHost)
        Log.d(TAG, "generateThreadTitle model=${config.modelId}, host=${config.apiHost}, providerId=$providerId")

        var baseUrl = config.toRetrofitBaseUrl()
        if (!baseUrl.endsWith("/")) baseUrl += "/"

        val localOpenAiCompat = isLocalOpenAiCompatibleProvider(providerId)
        val timeoutSec = if (localOpenAiCompat) 45 else 15

        val logging = HttpLoggingInterceptor()
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC)
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val api = retrofit.create(ChatApi::class.java)

        val requestMessages = ArrayList<ChatApi.ChatMessage>()
        val titlePrompt = "你是标题助手。根据输入生成一个中文短标题。\n" +
                "仅输出一个JSON对象，不要任何额外文本。\n" +
                "严格格式:{\"title\":\"3到12个字中文短标题\"}\n" +
                "约束: 不要标点，不要换行，不要解释。\n" +
                "输入:" + source
        requestMessages.add(ChatApi.ChatMessage("user", titlePrompt))

        val request = ChatApi.ChatRequest()
        request.model = config.modelId
        request.messages = requestMessages
        request.stream = false
        request.n = 1
        request.maxTokens = 512
        request.temperature = 0.0
        request.topP = 0.2
        applyModelDefaultsToRequest(request, providerId, config.modelId)
        request.stop = null
        request.thinking = if (localOpenAiCompat) java.lang.Boolean.FALSE else null
        request.reasoning = buildNoThinkingReasoning(providerId, localOpenAiCompat)
        if (!localOpenAiCompat) {
            val responseFormat = JsonObject()
            responseFormat.addProperty("type", "json_object")
            request.responseFormat = responseFormat
        } else {
            request.responseFormat = null
        }
        request.providerOptions = null

        val auth = if (config.apiKey != null && config.apiKey.trim().isNotEmpty())
            "Bearer " + config.apiKey.trim() else null
        val chatUrl = ApiUtils.toBaseUrl(config.apiHost, config.apiPath)
        Log.d(TAG, "generateThreadTitle url=$chatUrl"
                + ", promptLen=${source.length}"
                + ", maxTokens=${request.maxTokens}"
                + ", thinking=${request.thinking}"
                + ", reasoning=${request.reasoning?.toString() ?: "null"}")
        api.chatWithUrl(chatUrl, auth, "application/json", request).enqueue(object : retrofit2.Callback<ChatApi.ChatResponse> {
            override fun onResponse(
                call: retrofit2.Call<ChatApi.ChatResponse>,
                response: retrofit2.Response<ChatApi.ChatResponse>
            ) {
                val body314 = response.body()
                val choices314 = body314?.choices
                if (!response.isSuccessful || body314 == null || choices314 == null
                    || choices314.isEmpty() || choices314[0] == null
                    || choices314[0].message == null) {
                    var detail = ""
                    try {
                        if (response.errorBody() != null) {
                            detail = response.errorBody()!!.string()
                        }
                    } catch (ignored: Exception) {}
                    callback.onError(
                        context.getString(
                            R.string.error_naming_failed,
                            response.code().toString()
                        ) + if (detail.isEmpty()) "" else ("\n" + detail)
                    )
                    return
                }
                val raw = extractAssistantContent(body314)
                var title = extractTitleFromJsonOrText(raw)
                title = cleanTitleResult(title)
                if (title.length > 12) title = title.substring(0, 12)
                if (title.length < 3) title = if (source.length > 12) source.substring(0, 12) else source
                callback.onSuccess(title)
            }

            override fun onFailure(call: retrofit2.Call<ChatApi.ChatResponse>, t: Throwable) {
                callback.onError(t.message ?: "命名失败")
            }
        })
    }

    fun generateSessionOutline(history: List<Message>?, callback: ChatCallback) {
        val source = history ?: ArrayList()
        if (source.isEmpty()) {
            callback.onError("暂无可总结内容")
            return
        }
        val transcript = StringBuilder()
        val max = Math.min(10, source.size)
        for (i in 0 until max) {
            val m = source[i] ?: continue
            val role = if (m.role == Message.ROLE_USER) "用户" else "助手"
            var content = if (m.content != null) m.content.trim() else ""
            if (content.isEmpty()) continue
            if (content.length > 200) content = content.substring(0, 200) + "..."
            transcript.append(role).append("：").append(content).append("\n")
        }
        val prompt = transcript.toString().trim()
        if (prompt.isEmpty()) {
            callback.onError("暂无可总结内容")
            return
        }

        val config: AiModelConfig.ResolvedConfig
        try {
            config = AiModelConfig(context).getConfigForSummary()
        } catch (e: Exception) {
            callback.onError(context.getString(R.string.error_config_parse_failed, ""))
            return
        }
        if (config == null || !config.isValid()) {
            callback.onError(context.getString(R.string.error_no_summary_model_selected))
            return
        }

        var providerId = ""
        val summaryPreset = ModelConfig(context).getSummaryPreset()
        if (summaryPreset != null && summaryPreset.contains(":")) {
            providerId = summaryPreset.substring(0, summaryPreset.indexOf(':'))
        }
        providerId = resolveProviderId(providerId, config.apiHost)

        var baseUrl = config.toRetrofitBaseUrl()
        if (!baseUrl.endsWith("/")) baseUrl += "/"

        val localOpenAiCompat = isLocalOpenAiCompatibleProvider(providerId)
        val timeoutSec = if (localOpenAiCompat) 60 else 20

        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val api = retrofit.create(ChatApi::class.java)

        val requestMessages = ArrayList<ChatApi.ChatMessage>()
        requestMessages.add(ChatApi.ChatMessage("system",
            "你是对话大纲助手。请根据输入对话生成\u201C信息保真\u201D的大纲正文（80到320字），宁可稍长也不要遗漏关键信息。\n" +
                    "仅输出一个JSON对象，不要任何额外文本。\n" +
                    "严格格式:{\"outline\":\"...\"}\n" +
                    "强约束:\n" +
                    "1) 输出必须以 { 开始、以 } 结束。\n" +
                    "2) 只允许一个键 outline，不要额外键。\n" +
                    "3) 不要Markdown代码块，不要解释，不要Thinking/Reasoning文本。\n" +
                    "4) outline 内容不要标题，不要列表。\n" +
                    "5) 必须保留关键细节：人物/对象名称、核心事件、动机或目标、约束条件、结果或当前进展。\n" +
                    "6) 若原文出现时间、地点、数字、专有名词、规则设定，优先保留，不要泛化改写。\n" +
                    "7) 避免空泛词（如\u201C发生了一些事\u201D\u201C进行了讨论\u201D），改为具体事实。"))
        requestMessages.add(ChatApi.ChatMessage("user", prompt))

        val request = ChatApi.ChatRequest()
        request.model = config.modelId
        request.messages = requestMessages
        request.stream = false
        request.n = 1
        request.maxTokens = 620
        request.temperature = 0.2
        request.topP = 0.8
        applyModelDefaultsToRequest(request, providerId, config.modelId)
        request.stop = null
        request.thinking = if (localOpenAiCompat) java.lang.Boolean.FALSE else null
        request.reasoning = buildNoThinkingReasoning(providerId, localOpenAiCompat)
        if (!localOpenAiCompat) {
            val outlineResponseFormat = JsonObject()
            outlineResponseFormat.addProperty("type", "json_object")
            request.responseFormat = outlineResponseFormat
        } else {
            request.responseFormat = null
        }
        request.providerOptions = null

        val auth = if (config.apiKey != null && config.apiKey.trim().isNotEmpty())
            "Bearer " + config.apiKey.trim() else null
        val chatUrl = ApiUtils.toBaseUrl(config.apiHost, config.apiPath)
        api.chatWithUrl(chatUrl, auth, "application/json", request)
            .enqueue(object : retrofit2.Callback<ChatApi.ChatResponse> {
                override fun onResponse(
                    call: retrofit2.Call<ChatApi.ChatResponse>,
                    response: retrofit2.Response<ChatApi.ChatResponse>
                ) {
                    val body450 = response.body()
                    val choices450 = body450?.choices
                    if (!response.isSuccessful || body450 == null || choices450 == null
                        || choices450.isEmpty() || choices450[0] == null
                        || choices450[0].message == null) {
                        var detail = ""
                        try {
                            if (response.errorBody() != null) {
                                detail = response.errorBody()!!.string()
                            }
                        } catch (ignored: Exception) {}
                        callback.onError("生成大纲失败: " + response.code()
                                + if (detail.isEmpty()) "" else ("\n" + detail))
                        return
                    }
                    var outline = extractAssistantContent(body450)
                    outline = extractTextFieldFromJsonOrText(outline, "outline", "summary", "content", "result")
                    outline = stripThinkTags(outline).replace("\n", " ").trim()
                    if (outline.isEmpty()) {
                        callback.onError("生成大纲失败")
                        return
                    }
                    callback.onSuccess(outline)
                }

                override fun onFailure(call: retrofit2.Call<ChatApi.ChatResponse>, t: Throwable) {
                    callback.onError(t.message ?: "生成大纲失败")
                }
            })
    }

    fun summarizeMessageForOutline(content: String?, callback: ChatCallback) {
        var source = content?.trim() ?: ""
        if (source.isEmpty()) {
            callback.onError(context.getString(R.string.error_message_empty))
            return
        }
        if (source.length > 2500) {
            source = source.substring(0, 2500)
        }
        val config: AiModelConfig.ResolvedConfig
        try {
            config = AiModelConfig(context).getConfigForSummary()
        } catch (e: Exception) {
            callback.onError(context.getString(R.string.error_config_parse_failed, ""))
            return
        }
        if (config == null || !config.isValid()) {
            callback.onError(context.getString(R.string.error_no_summary_model_selected))
            return
        }

        var providerId = ""
        val summaryPreset = ModelConfig(context).getSummaryPreset()
        if (summaryPreset != null && summaryPreset.contains(":")) {
            providerId = summaryPreset.substring(0, summaryPreset.indexOf(':'))
        }
        providerId = resolveProviderId(providerId, config.apiHost)

        var baseUrl = config.toRetrofitBaseUrl()
        if (!baseUrl.endsWith("/")) baseUrl += "/"

        val localOpenAiCompat = isLocalOpenAiCompatibleProvider(providerId)
        val timeoutSec = if (localOpenAiCompat) 60 else 20

        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val api = retrofit.create(ChatApi::class.java)

        val requestMessages = ArrayList<ChatApi.ChatMessage>()
        requestMessages.add(ChatApi.ChatMessage("system",
            "你是小说写作助手。请把输入内容提炼为可放入大纲的条目正文（80到280字），要求细节充分、便于后续续写。\n" +
                    "仅输出一个JSON对象，不要任何额外文本。\n" +
                    "严格格式:{\"summary\":\"...\"}\n" +
                    "强约束:\n" +
                    "1) 输出必须以 { 开始、以 } 结束。\n" +
                    "2) 只允许一个键 summary，不要额外键。\n" +
                    "3) 不要Markdown代码块，不要解释，不要Thinking/Reasoning文本。\n" +
                    "4) summary 内容不要标题，不要列表。\n" +
                    "5) 必须覆盖：关键事件经过、人物意图/冲突、重要设定或规则、任务线索与阶段结果。\n" +
                    "6) 保留可复用细节：时间地点、名称称谓、数字阈值、道具/能力/组织名等。\n" +
                    "7) 不要只写结论，需包含必要过程与因果关系。"))
        requestMessages.add(ChatApi.ChatMessage("user", source))

        val request = ChatApi.ChatRequest()
        request.model = config.modelId
        request.messages = requestMessages
        request.stream = false
        request.n = 1
        request.maxTokens = 520
        request.temperature = 0.2
        request.topP = 0.8
        applyModelDefaultsToRequest(request, providerId, config.modelId)
        request.stop = null
        request.thinking = if (localOpenAiCompat) java.lang.Boolean.FALSE else null
        request.reasoning = buildNoThinkingReasoning(providerId, localOpenAiCompat)
        if (!localOpenAiCompat) {
            val summaryResponseFormat = JsonObject()
            summaryResponseFormat.addProperty("type", "json_object")
            request.responseFormat = summaryResponseFormat
        } else {
            request.responseFormat = null
        }
        request.providerOptions = null

        val auth = if (config.apiKey != null && config.apiKey.trim().isNotEmpty())
            "Bearer " + config.apiKey.trim() else null
        val chatUrl = ApiUtils.toBaseUrl(config.apiHost, config.apiPath)
        api.chatWithUrl(chatUrl, auth, "application/json", request)
            .enqueue(object : retrofit2.Callback<ChatApi.ChatResponse> {
                override fun onResponse(
                    call: retrofit2.Call<ChatApi.ChatResponse>,
                    response: retrofit2.Response<ChatApi.ChatResponse>
                ) {
                    val body571 = response.body()
                    val choices571 = body571?.choices
                    if (!response.isSuccessful || body571 == null || choices571 == null
                        || choices571.isEmpty() || choices571[0] == null
                        || choices571[0].message == null) {
                        var detail = ""
                        try {
                            if (response.errorBody() != null) {
                                detail = response.errorBody()!!.string()
                            }
                        } catch (ignored: Exception) {}
                        callback.onError("总结失败: " + response.code()
                                + if (detail.isEmpty()) "" else ("\n" + detail))
                        return
                    }
                    var summary = extractAssistantContent(body571)
                    summary = extractTextFieldFromJsonOrText(summary, "summary", "outline", "content", "result")
                    summary = stripThinkTags(summary).replace("\n", " ").trim()
                    if (summary.isEmpty()) {
                        callback.onError("总结失败")
                        return
                    }
                    callback.onSuccess(summary)
                }

                override fun onFailure(call: retrofit2.Call<ChatApi.ChatResponse>, t: Throwable) {
                    callback.onError(t.message ?: "总结失败")
                }
            })
    }

    fun generateChapterPlanJson(ctx: ChapterPlanContext, callback: ChatCallback) {
        val targetTitle = ctx.targetTitle.trim()
        if (targetTitle.isEmpty()) {
            callback.onError("目标章节标题为空")
            return
        }

        val config: AiModelConfig.ResolvedConfig
        try {
            config = AiModelConfig(context).getConfigForNovelSharp()
        } catch (e: Exception) {
            callback.onError("配置解析失败")
            return
        }
        if (config == null || !config.isValid()) {
            callback.onError(context.getString(R.string.error_no_novel_model_selected))
            return
        }

        var providerId = ""
        val preset = ModelConfig(context).getNovelSharpPreset()
        if (preset != null && preset.contains(":")) {
            providerId = preset.substring(0, preset.indexOf(':'))
        }
        providerId = resolveProviderId(providerId, config.apiHost)

        var baseUrl = config.toRetrofitBaseUrl()
        if (!baseUrl.endsWith("/")) baseUrl += "/"

        val localOpenAiCompat = isLocalOpenAiCompatibleProvider(providerId)
        val timeoutSec = if (localOpenAiCompat) 60 else 45

        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val api = retrofit.create(ChatApi::class.java)

        val requestMessages = ArrayList<ChatApi.ChatMessage>()
        requestMessages.add(ChatApi.ChatMessage("system",
            "你是小说章节规划助手。\n" +
                    "你将收到：(a) 一段按类型分组的大纲上下文（含章节序列、人物、世界、知情等），(b) 一个明确的【本次必须规划的章节】标题。\n" +
                    "你的唯一任务：为【该目标章节本身】（不是它的前一章，也不是它的下一章）输出一个结构化写作计划。\n\n" +
                    "仅输出一个 JSON 对象（绝对禁止 Markdown 代码块、解释、Thinking 文本）：\n" +
                    "{\"chapterGoal\":\"\",\"startState\":\"\",\"endState\":\"\",\"characterDrives\":[],\"knowledgeBoundary\":[],\"eventChain\":[],\"foreshadow\":[],\"payoff\":[],\"forbidden\":[],\"styleGuide\":\"\",\"targetLength\":\"\"}\n\n" +
                    "字段语义（务必严格匹配，前端会一一回填到对应输入框）：\n" +
                    "- chapterGoal: 字符串，本章核心目标（≤120字）。\n" +
                    "- startState: 字符串，本章开场时的人物/局面状态。\n" +
                    "- endState: 字符串，本章收尾时的状态，需与 startState 形成可见对比。\n" +
                    "- characterDrives: 对象数组 [{\"name\":\"\",\"goal\":\"\",\"misbelief\":\"\",\"emotion\":\"\"}]，每个出场关键角色一项；name 必填。\n" +
                    "- knowledgeBoundary: 字符串数组；每条一行短陈述，形如 \"X 知道/不知道/误以为 Y\"。\n" +
                    "- eventChain: 字符串数组（3-7 项），按时间顺序，每条形如 \"起因 → 行为 → 结果\"，不得照抄前文已发生事件。\n" +
                    "- foreshadow: 字符串数组，本章埋下的伏笔。\n" +
                    "- payoff: 字符串数组，本章兑现/回收的伏笔。\n" +
                    "- forbidden: 字符串数组，本章不应写的内容（剧透、违反人设的动作、跳跃式叙述等）。\n" +
                    "- styleGuide: 字符串，文风 / 节奏 / 视角提示。\n" +
                    "- targetLength: 字符串（即使是数字也用引号），例如 \"3000\"。\n\n" +
                    "强约束（违反任意一条都视为失败）：\n" +
                    "1) 输出必须以 { 开头、以 } 结尾，必须保留全部 11 个键；空值用 \"\" 或 [] 占位。\n" +
                    "2) 严禁把目标章节误解成「下一章 / 续写章节」——你输出的计划就是用户指定的那一章本身。\n" +
                    "3) 若是【覆盖】模式，请基于「目标章节当前大纲」做重写或细化；不是为后续章节做规划。\n" +
                    "4) 计划要呼应章节序列中【本次必须规划的章节】所在位置——之前章节是已发生事实，之后章节（如有）是未来约束。\n" +
                    "5) 不得违背【知情约束】：角色只能基于其已知信息行动；让某角色得知新信息需在 eventChain 中给出获取路径。\n" +
                    "6) 内容具体可执行；避免「角色继续推进剧情」之类的空话。"))
        requestMessages.add(ChatApi.ChatMessage("user", buildChapterPlanUserPrompt(ctx)))

        val request = ChatApi.ChatRequest()
        request.model = config.modelId
        request.messages = requestMessages
        request.stream = false
        request.n = 1
        request.maxTokens = 800
        request.temperature = 0.15
        request.topP = 0.6
        applyModelDefaultsToRequest(request, providerId, config.modelId)
        request.stop = null
        // Keep chapter-plan request minimal for broad compatibility and lower latency.
        request.thinking = null
        request.reasoning = null
        request.responseFormat = null
        request.providerOptions = null

        val auth = if (config.apiKey != null && config.apiKey.trim().isNotEmpty())
            "Bearer " + config.apiKey.trim() else null
        val chatUrl = ApiUtils.toBaseUrl(config.apiHost, config.apiPath)
        Log.d(TAG, "generateChapterPlanJson providerId=$providerId"
                + ", model=${config.modelId}"
                + ", localOpenAiCompat=$localOpenAiCompat"
                + ", thinking=${request.thinking}"
                + ", reasoning=${request.reasoning?.toString() ?: "null"}"
                + ", responseFormat=${request.responseFormat?.toString() ?: "null"}")
        callback.onPartial("正在请求章节计划模型…")
        requestChapterPlanWithFallback(api, chatUrl, auth, request, callback, true)
    }

    /**
     * 构建 user prompt：把目标章节、章节序列、人物/世界/知情/资料、最近对话、用户提示
     * 按结构化段落输出，让模型既知道要规划哪一章、又能锚定到大纲位置。
     */
    private fun buildChapterPlanUserPrompt(ctx: ChapterPlanContext): String {
        val targetTitle = ctx.targetTitle.trim()
        val mode = if (ctx.isExisting) "覆盖已有计划" else "新建续写章节"
        val sb = StringBuilder()
        // 把目标章节定为头条信息 + 醒目箭头 + 重复声明，避免模型把它误解为「下一章」。
        sb.append("============================\n")
        sb.append("【本次必须规划的章节】").append(targetTitle).append("\n")
        sb.append("【模式】").append(mode).append("\n")
        sb.append("⚠️ 你的所有输出仅围绕上面这一章；不是它的前一章，不是它的下一章。\n")
        sb.append("============================\n")

        if (ctx.isExisting && ctx.existingContent.trim().isNotEmpty()) {
            sb.append("\n【目标章节当前大纲（你需要重写/细化的内容）】\n")
                .append(truncate(ctx.existingContent.trim(), 800))
                .append("\n")
        }

        if (ctx.allChapters.isNotEmpty()) {
            sb.append("\n【章节序列上下文 — 仅供定位，不是规划目标】\n")
            var matchedTarget = false
            val perChapterCap = 240
            for ((idx, item) in ctx.allChapters.withIndex()) {
                val itemTitle = item.title?.trim().orEmpty()
                val itemContent = item.content?.trim().orEmpty()
                val isTarget = ctx.isExisting && itemTitle == targetTitle && !matchedTarget
                if (isTarget) matchedTarget = true
                sb.append(idx + 1).append(". ")
                if (isTarget) sb.append("◆◆ 这就是目标章节 ◆◆ ")
                sb.append(if (itemTitle.isEmpty()) "(无标题)" else itemTitle)
                if (itemContent.isNotEmpty()) {
                    sb.append("：").append(truncate(itemContent.replace("\n", " "), perChapterCap))
                }
                sb.append("\n")
            }
            if (!ctx.isExisting) {
                sb.append(ctx.allChapters.size + 1).append(". ◆◆ 这就是目标章节（新建续写） ◆◆ ").append(targetTitle).append("\n")
            }
        } else if (!ctx.isExisting) {
            sb.append("\n【章节序列上下文】（暂无章节，本章为开篇）\n")
        }

        appendOutlineSection(sb, "人物资料", ctx.characters, perItemCap = 320)
        appendOutlineSection(sb, "世界背景", ctx.worlds, perItemCap = 320, hideTitle = true)
        appendOutlineSection(sb, "知情约束", ctx.knowledgeConstraints, perItemCap = 240)
        appendOutlineSection(sb, "其他资料", ctx.materials, perItemCap = 240, hideTitle = true)

        val dlg = ctx.recentDialogue.trim()
        if (dlg.isNotEmpty()) {
            sb.append("\n【最近对话节选（按时间顺序）】\n").append(truncate(dlg, 1500)).append("\n")
        }

        val hint = ctx.userHint.trim()
        if (hint.isNotEmpty()) {
            sb.append("\n【本章用户补充指示】\n").append(truncate(hint, 600)).append("\n")
        }

        val target = ctx.targetLength.trim()
        if (target.isNotEmpty()) {
            sb.append("\n【期望篇幅】").append(target).append("（请将此值写入 targetLength 字段）\n")
        }

        // 末尾再强调一次目标章节，模型在长 prompt 中往往关注首尾。
        sb.append("\n============================\n")
        sb.append("提醒：现在请输出【").append(targetTitle).append("】这一章的写作计划 JSON。\n")
        sb.append("============================\n")

        // Soft cap to keep request reasonable; keep head + tail to preserve target+latest context.
        return softCap(sb.toString(), 7800)
    }

    private fun appendOutlineSection(
        sb: StringBuilder,
        label: String,
        items: List<SessionOutlineItem>,
        perItemCap: Int,
        hideTitle: Boolean = false,
    ) {
        if (items.isEmpty()) return
        sb.append("\n【").append(label).append("】\n")
        for (item in items) {
            val title = item.title?.trim().orEmpty()
            val content = item.content?.trim().orEmpty()
            if (title.isEmpty() && content.isEmpty()) continue
            sb.append("- ")
            if (!hideTitle && title.isNotEmpty()) sb.append(title).append("：")
            if (content.isNotEmpty()) sb.append(truncate(content.replace("\n", " "), perItemCap))
            sb.append("\n")
        }
    }

    private fun truncate(s: String, max: Int): String {
        if (s.length <= max) return s
        return s.substring(0, max) + "…"
    }

    private fun softCap(s: String, max: Int): String {
        if (s.length <= max) return s
        val head = s.substring(0, max - 200)
        val tail = s.substring(s.length - 200)
        return head + "\n…(中段省略)…\n" + tail
    }

    private fun requestChapterPlanWithFallback(
        api: ChatApi,
        chatUrl: String,
        auth: String?,
        request: ChatApi.ChatRequest,
        callback: ChatCallback,
        allowFallback: Boolean
    ) {
        api.chatWithUrl(chatUrl, auth, "application/json", request)
            .enqueue(object : retrofit2.Callback<ChatApi.ChatResponse> {
                override fun onResponse(
                    call: retrofit2.Call<ChatApi.ChatResponse>,
                    response: retrofit2.Response<ChatApi.ChatResponse>
                ) {
                    val body707 = response.body()
                    val choices707 = body707?.choices
                    if (!response.isSuccessful || body707 == null || choices707 == null
                        || choices707.isEmpty() || choices707[0] == null
                        || choices707[0].message == null) {
                        var detail = ""
                        try {
                            if (response.errorBody() != null) {
                                detail = response.errorBody()!!.string()
                            }
                        } catch (ignored: Exception) {}
                        if (allowFallback && shouldRetryWithoutAdvancedParams(detail)) {
                            callback.onPartial("参数兼容中，正在重试…")
                            Log.w(TAG, "chapter plan retry without advanced params, detail=$detail")
                            requestChapterPlanWithFallback(
                                api,
                                chatUrl,
                                auth,
                                buildChapterPlanFallbackRequest(request),
                                callback,
                                false
                            )
                            return
                        }
                        callback.onError("章节计划生成失败: " + response.code()
                                + if (detail.isEmpty()) "" else ("\n" + detail))
                        return
                    }
                    callback.onPartial("模型已返回，正在解析计划…")
                    val raw = extractAssistantContent(body707)
                    Log.d(TAG, "chapter plan raw length=${raw?.length ?: 0}"
                            + ", preview=${previewForLog(raw, 180)}")
                    val obj = parseFirstJsonObject(raw)
                    if (obj == null) {
                        val preview = raw?.trim() ?: ""
                        var head = preview
                        var tail = ""
                        if (head.length > 120) {
                            head = head.substring(0, 120) + "..."
                            val start = Math.max(0, preview.length - 120)
                            tail = "...\n末尾片段: " + preview.substring(start)
                        }
                        callback.onError("章节计划解析失败" +
                                if (preview.isEmpty()) "" else ("\n返回长度: " + preview.length
                                        + "\n开头片段: " + head + tail))
                        return
                    }
                    callback.onPartial("章节计划已生成")
                    val normalized = normalizeChapterPlanJson(obj)
                    Log.d(TAG, "chapter plan normalized nonEmptyFields=${countNonEmptyPlanFields(normalized)}"
                            + ", payload=${previewForLog(normalized.toString(), 220)}")
                    callback.onSuccess(normalized.toString())
                }

                override fun onFailure(call: retrofit2.Call<ChatApi.ChatResponse>, t: Throwable) {
                    val reason = t.message ?: "章节计划生成失败"
                    callback.onError("章节计划生成失败(${request.model}): $reason")
                }
            })
    }

    private fun buildChapterPlanFallbackRequest(source: ChatApi.ChatRequest?): ChatApi.ChatRequest {
        val request = ChatApi.ChatRequest()
        request.model = source?.model
        request.messages = source?.messages
        request.stream = false
        request.n = null
        request.maxTokens = source?.maxTokens
        request.temperature = null
        request.topP = null
        request.stop = null
        request.thinking = null
        request.reasoning = null
        request.responseFormat = null
        request.providerOptions = null
        return request
    }

    private fun shouldRetryWithoutAdvancedParams(detail: String?): Boolean {
        if (detail == null || detail.trim().isEmpty()) return false
        val lower = detail.lowercase(java.util.Locale.ROOT)
        if (lower.contains("invalid_request_error")) return true
        if (lower.contains("unknown parameter")) return true
        if (lower.contains("invalid parameter")) return true
        if (lower.contains("unsupported parameter")) return true
        if (lower.contains("response_format")) return true
        if (lower.contains("reasoning")) return true
        if (lower.contains("thinking")) return true
        if (lower.contains("temperature")) return true
        return lower.contains("top_p")
    }

    private fun parseFirstJsonObject(raw: String?): JsonObject? {
        val text = sanitizeJsonLikeText(stripThinkTags(raw))
        if (text.isEmpty()) return null
        // 1) Full parse first: parse the whole payload as a JSON object.
        val direct = tryParseObject(text)
        if (direct != null) return direct

        // 2) Full-slice parse: from first '{' to last '}' as one complete object.
        val fullSlice = extractJsonObjectSlice(text)
        val fullObj = tryParseObject(fullSlice)
        if (fullObj != null) return fullObj

        // 3) Only if likely truncated/non-normal ending, run fallback extraction.
        if (looksLikeTruncatedJson(text)) {
            val repaired = repairTruncatedJsonObject(text)
            val repairedObj = tryParseObject(repaired)
            if (repairedObj != null) return repairedObj
            val keywordObj = extractChapterPlanByKeywords(text)
            if (keywordObj != null) return keywordObj
        }
        return null
    }

    private fun tryParseObject(text: String?): JsonObject? {
        if (text == null || text.trim().isEmpty()) return null
        return try {
            JsonParser().parse(text).asJsonObject
        } catch (ignored: Exception) {
            null
        }
    }

    private fun looksLikeTruncatedJson(text: String?): Boolean {
        if (text == null || text.isEmpty()) return false
        val first = text.indexOf('{')
        if (first < 0) return false
        var objDepth = 0
        var arrDepth = 0
        var inString = false
        var escaped = false
        for (i in first until text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\') {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (c) {
                '{' -> objDepth++
                '}' -> objDepth = Math.max(0, objDepth - 1)
                '[' -> arrDepth++
                ']' -> arrDepth = Math.max(0, arrDepth - 1)
            }
        }
        return inString || objDepth > 0 || arrDepth > 0
    }

    private fun extractChapterPlanByKeywords(text: String?): JsonObject? {
        if (text == null || text.isEmpty()) return null
        val out = JsonObject()

        putIfNotEmpty(out, "chapterGoal", extractStringByKeys(text,
            "chapterGoal", "chapter_goal", "goal", "章节目标", "本章目标", "目标"))
        putIfNotEmpty(out, "startState", extractStringByKeys(text,
            "startState", "start_state", "起始状态", "开场状态", "开局状态"))
        putIfNotEmpty(out, "endState", extractStringByKeys(text,
            "endState", "end_state", "结束状态", "结尾状态", "收束状态"))
        putIfNotEmpty(out, "styleGuide", extractStringByKeys(text,
            "styleGuide", "style_guide", "style", "writingStyle", "文风", "文风与节奏"))

        putArrayIfNotEmpty(out, "knowledgeBoundary", extractArrayByKeys(text,
            "knowledgeBoundary", "knowledge_boundary", "knowledge", "知情边界", "知情约束"))
        putArrayIfNotEmpty(out, "eventChain", extractArrayByKeys(text,
            "eventChain", "event_chain", "events", "事件链", "关键事件"))
        putArrayIfNotEmpty(out, "foreshadow", extractArrayByKeys(text,
            "foreshadow", "foreshadows", "伏笔"))
        putArrayIfNotEmpty(out, "payoff", extractArrayByKeys(text,
            "payoff", "payoffs", "回收"))
        putArrayIfNotEmpty(out, "forbidden", extractArrayByKeys(text,
            "forbidden", "forbiddenList", "禁写清单", "禁写", "禁忌"))
        putCharacterDrivesIfNotEmpty(out, extractArrayByKeys(text,
            "characterDrives", "character_drives", "characters", "角色驱动", "角色动机"))

        return if (out.entrySet().isEmpty()) null else out
    }

    private fun putIfNotEmpty(obj: JsonObject?, key: String?, value: String?) {
        if (obj == null || key == null) return
        if (value == null || value.trim().isEmpty()) return
        obj.addProperty(key, value.trim())
    }

    private fun putArrayIfNotEmpty(obj: JsonObject?, key: String?, values: List<String>?) {
        if (obj == null || key == null || values == null || values.isEmpty()) return
        val arr = JsonArray()
        for (v in values) {
            if (v == null || v.trim().isEmpty()) continue
            arr.add(v.trim())
        }
        if (arr.size() > 0) obj.add(key, arr)
    }

    private fun putCharacterDrivesIfNotEmpty(obj: JsonObject?, drives: List<String>?) {
        if (obj == null || drives == null || drives.isEmpty()) return
        val arr = JsonArray()
        for (v in drives) {
            if (v == null || v.trim().isEmpty()) continue
            val one = JsonObject()
            one.addProperty("name", "")
            one.addProperty("goal", v.trim())
            one.addProperty("misbelief", "")
            one.addProperty("emotion", "")
            arr.add(one)
        }
        if (arr.size() > 0) obj.add("characterDrives", arr)
    }

    private fun extractStringByKeys(text: String?, vararg keys: String): String {
        if (text == null) return ""
        for (key in keys) {
            if (key.isEmpty()) continue
            val p = java.util.regex.Pattern.compile(
                "\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"",
                java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.DOTALL
            )
            val m = p.matcher(text)
            if (m.find()) {
                val v = m.group(1)
                if (v != null && v.trim().isNotEmpty()) return v.trim()
            }
        }
        return ""
    }

    private fun extractArrayByKeys(text: String?, vararg keys: String): List<String> {
        val out = ArrayList<String>()
        if (text == null) return out
        for (key in keys) {
            if (key.isEmpty()) continue
            val p = java.util.regex.Pattern.compile(
                "\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\\[(.*?)\\]",
                java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.DOTALL
            )
            val m = p.matcher(text)
            if (!m.find()) continue
            val body = m.group(1)
            if (body == null || body.trim().isEmpty()) continue
            val item = java.util.regex.Pattern
                .compile("\"([^\"]*)\"")
                .matcher(body)
            while (item.find()) {
                val v = item.group(1)
                if (v != null && v.trim().isNotEmpty()) out.add(v.trim())
            }
            if (out.isNotEmpty()) return out
        }
        return out
    }

    private fun sanitizeJsonLikeText(text: String?): String {
        var out = text?.trim() ?: ""
        if (out.isEmpty()) return ""
        // Remove fenced code markers.
        out = out.replace(Regex("(?is)^```(?:json)?\\s*"), "")
        out = out.replace(Regex("(?is)\\s*```$"), "")
        // Normalize full-width punctuation often seen in CJK outputs.
        out = out.replace('\u201C', '"').replace('\u201D', '"')
            .replace('\u2018', '\'').replace('\u2019', '\'')
            .replace('：', ':')
            .replace('，', ',')
        return out.trim()
    }

    private fun repairJsonCandidate(candidate: String?): String {
        var out = sanitizeJsonLikeText(candidate)
        if (out.isEmpty()) return ""
        // Try converting single-quoted JSON-like text to valid double-quoted JSON.
        out = out.replace(Regex("(?<!\\\\)'"), "\"")
        // Remove trailing commas before closing braces/brackets.
        out = out.replace(Regex(",\\s*([}\\]])"), "$1")
        return out
    }

    private fun repairTruncatedJsonObject(raw: String?): String {
        if (raw == null || raw.isEmpty()) return ""
        val start = raw.indexOf('{')
        if (start < 0) return ""
        val text = raw.substring(start)
        val out = StringBuilder(text)
        val closers = java.util.ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\') {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (c) {
                '{' -> closers.push('}')
                '[' -> closers.push(']')
                '}', ']' -> {
                    if (closers.isNotEmpty() && closers.peek() == c) closers.pop()
                    else return ""
                }
            }
        }
        if (inString) return ""
        while (closers.isNotEmpty()) out.append(closers.pop())
        val fixed = out.toString().replace(Regex(",\\s*([}\\]])"), "$1")
        return fixed
    }


    /**
     * 基于一段章节计划（含人物/世界/知情等上下文）生成一篇卷大纲。
     * 输出纯文本（不强 JSON），方便用户编辑、AI 复读时阅读。
     *
     * @param volumeTitle 卷标题，用于 prompt 中明确目标范围
     * @param coverageRange 形如 "章节1 ~ 章节10"
     * @param promptContext OutlinePromptBuilder.buildFull 输出的上下文
     */
    fun generateVolumeOutline(
        volumeTitle: String,
        coverageRange: String,
        promptContext: String,
        callback: ChatCallback,
    ) {
        val context0 = promptContext.trim()
        if (context0.isEmpty()) {
            callback.onError("上下文为空，无法生成卷纲")
            return
        }
        val config: AiModelConfig.ResolvedConfig
        try {
            config = AiModelConfig(context).getConfigForSummary()
        } catch (e: Exception) {
            callback.onError(this.context.getString(R.string.error_config_parse_failed, ""))
            return
        }
        if (config == null || !config.isValid()) {
            callback.onError(this.context.getString(R.string.error_no_summary_model_selected))
            return
        }

        var providerId = ""
        val summaryPreset = ModelConfig(this.context).getSummaryPreset()
        if (summaryPreset != null && summaryPreset.contains(":")) {
            providerId = summaryPreset.substring(0, summaryPreset.indexOf(':'))
        }
        providerId = resolveProviderId(providerId, config.apiHost)

        var baseUrl = config.toRetrofitBaseUrl()
        if (!baseUrl.endsWith("/")) baseUrl += "/"

        val localOpenAiCompat = isLocalOpenAiCompatibleProvider(providerId)
        val timeoutSec = if (localOpenAiCompat) 60 else 30

        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val api = retrofit.create(ChatApi::class.java)

        val requestMessages = ArrayList<ChatApi.ChatMessage>()
        requestMessages.add(ChatApi.ChatMessage("system",
            "你是小说写作助手。请把以下覆盖范围内的章节计划合并成一篇“卷大纲”。\n" +
                    "目标：替代多章细节，保留主线推进、人物状态、关键事件、伏笔/回收、知情边界关键变化。\n" +
                    "硬约束：\n" +
                    "1) 输出纯文本中文，不要 Markdown 代码块。\n" +
                    "2) 控制在 600 字以内，分段使用【小标题】方式（如【主线推进】【人物状态】【关键事件】【伏笔】【知情边界变化】）。\n" +
                    "3) 不要凭空添加未在输入中提到的事件或角色。\n" +
                    "4) 不要 Thinking/Reasoning 文本。"))
        requestMessages.add(ChatApi.ChatMessage("user",
            "【目标卷标题】" + volumeTitle + "\n" +
                    "【覆盖范围】" + coverageRange + "\n\n" +
                    context0))

        val request = ChatApi.ChatRequest()
        request.model = config.modelId
        request.messages = requestMessages
        request.stream = false
        request.n = 1
        request.maxTokens = 1200
        request.temperature = 0.2
        request.topP = 0.7
        applyModelDefaultsToRequest(request, providerId, config.modelId)
        request.stop = null
        request.thinking = if (localOpenAiCompat) java.lang.Boolean.FALSE else null
        request.reasoning = buildNoThinkingReasoning(providerId, localOpenAiCompat)
        request.responseFormat = null
        request.providerOptions = null

        val auth = if (config.apiKey != null && config.apiKey.trim().isNotEmpty())
            "Bearer " + config.apiKey.trim() else null
        val chatUrl = ApiUtils.toBaseUrl(config.apiHost, config.apiPath)
        api.chatWithUrl(chatUrl, auth, "application/json", request)
            .enqueue(object : retrofit2.Callback<ChatApi.ChatResponse> {
                override fun onResponse(
                    call: retrofit2.Call<ChatApi.ChatResponse>,
                    response: retrofit2.Response<ChatApi.ChatResponse>
                ) {
                    val body = response.body()
                    val choices = body?.choices
                    if (!response.isSuccessful || body == null || choices == null
                        || choices.isEmpty() || choices[0] == null
                        || choices[0].message == null) {
                        var detail = ""
                        try {
                            if (response.errorBody() != null) detail = response.errorBody()!!.string()
                        } catch (ignored: Exception) {}
                        callback.onError("卷纲生成失败: " + response.code()
                                + if (detail.isEmpty()) "" else ("\n" + detail))
                        return
                    }
                    var result = extractAssistantContent(body)
                    result = stripThinkTags(result).trim()
                    if (result.isEmpty()) { callback.onError("卷纲生成失败"); return }
                    callback.onSuccess(result)
                }

                override fun onFailure(call: retrofit2.Call<ChatApi.ChatResponse>, t: Throwable) {
                    callback.onError(t.message ?: "卷纲生成失败")
                }
            })
    }

    /**
     * 从已有大纲（章节计划 + 人物 + 世界）提取每章的知情约束。
     * 输入是结构化大纲文本（由 OutlinePromptBuilder 构造）。
     * 输出 JSON 数组，每条带 chapter 字段标明所属章节（"通用"=跨章节）。
     */
    fun extractKnowledgeConstraints(outlineText: String?, callback: ChatCallback) {
        val outline = outlineText?.trim() ?: ""
        if (outline.isEmpty()) {
            callback.onError("大纲为空，无法提取知情约束")
            return
        }

        val config: AiModelConfig.ResolvedConfig
        try {
            config = AiModelConfig(context).getConfigForSummary()
        } catch (e: Exception) {
            callback.onError(context.getString(R.string.error_config_parse_failed, ""))
            return
        }
        if (config == null || !config.isValid()) {
            callback.onError(context.getString(R.string.error_no_summary_model_selected))
            return
        }

        var providerId = ""
        val summaryPreset = ModelConfig(context).getSummaryPreset()
        if (summaryPreset != null && summaryPreset.contains(":")) {
            providerId = summaryPreset.substring(0, summaryPreset.indexOf(':'))
        }
        providerId = resolveProviderId(providerId, config.apiHost)

        var baseUrl = config.toRetrofitBaseUrl()
        if (!baseUrl.endsWith("/")) baseUrl += "/"

        val localOpenAiCompat = isLocalOpenAiCompatibleProvider(providerId)
        val timeoutSec = if (localOpenAiCompat) 60 else 20

        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val api = retrofit.create(ChatApi::class.java)

        val requestMessages = ArrayList<ChatApi.ChatMessage>()
        requestMessages.add(ChatApi.ChatMessage("system",
            "你是小说写作的【知情边界提取助手】。我会给你一段按类型分组的小说大纲（章节大纲 / 人物资料 / 世界背景 / 已有知情约束）。\n" +
                    "你的任务：基于其中事实，为大纲中实际出现的章节生成「知情边界条目」，作为主写作模型生成正文时必须严守的硬约束。\n\n" +
                    "仅输出一个 JSON 对象（不要 Markdown、不要解释、不要 Thinking 文本）：\n" +
                    "{\"items\":[{\"chapter\":\"章节标题或'通用'\",\"title\":\"角色名 - 信息点\",\"content\":\"陈述句\"}, ...]}\n\n" +
                    "强约束：\n" +
                    "1) 输出必须以 { 开头、以 } 结尾。\n" +
                    "2) chapter 必须是大纲【章节大纲】里真实出现的标题原文；适用于多章/跨时段写「通用」。\n" +
                    "3) title 严格形式：\"角色名 - 信息点\"。信息点为名词短语，不要带「知道/不知道」等动词。\n" +
                    "4) content 是单句陈述，主语为 title 中的角色，结构为 \"X 知道 Y\" / \"X 不知道 Y\" / \"X 误以为 Y\"，不要解释推理过程。\n" +
                    "5) 重点抓：秘密、伏笔、信息差、需某事件后才得知的事；忽略全员常识与无悬念的公开事件。\n" +
                    "6) 同一 (chapter, 角色名, 信息点) 不得重复；items 总数 ≤ 15；单条 content ≤ 60 字。\n" +
                    "7) 推不出的条目不要输出；不要捏造大纲未提到的信息。\n" +
                    "8) 若提供了【目标章节范围】小节，items 的 chapter 字段必须取自该范围（外加可选的「通用」）。"))
        requestMessages.add(ChatApi.ChatMessage("user", outline))

        val request = ChatApi.ChatRequest()
        request.model = config.modelId
        request.messages = requestMessages
        request.stream = false
        request.n = 1
        request.maxTokens = 1500
        request.temperature = 0.3
        request.topP = 0.8
        applyModelDefaultsToRequest(request, providerId, config.modelId)
        request.stop = null
        request.thinking = if (localOpenAiCompat) java.lang.Boolean.FALSE else null
        request.reasoning = buildNoThinkingReasoning(providerId, localOpenAiCompat)
        if (!localOpenAiCompat) {
            val fmt = JsonObject()
            fmt.addProperty("type", "json_object")
            request.responseFormat = fmt
        } else {
            request.responseFormat = null
        }
        request.providerOptions = null

        val auth = if (config.apiKey != null && config.apiKey.trim().isNotEmpty())
            "Bearer " + config.apiKey.trim() else null
        val chatUrl = ApiUtils.toBaseUrl(config.apiHost, config.apiPath)
        api.chatWithUrl(chatUrl, auth, "application/json", request)
            .enqueue(object : retrofit2.Callback<ChatApi.ChatResponse> {
                override fun onResponse(
                    call: retrofit2.Call<ChatApi.ChatResponse>,
                    response: retrofit2.Response<ChatApi.ChatResponse>
                ) {
                    val body = response.body()
                    val choices = body?.choices
                    if (!response.isSuccessful || body == null || choices == null
                        || choices.isEmpty() || choices[0] == null
                        || choices[0].message == null) {
                        var detail = ""
                        try {
                            if (response.errorBody() != null) {
                                detail = response.errorBody()!!.string()
                            }
                        } catch (ignored: Exception) {}
                        callback.onError("提取失败: " + response.code()
                                + if (detail.isEmpty()) "" else ("\n" + detail))
                        return
                    }
                    var result = extractAssistantContent(body)
                    result = stripThinkTags(result).trim()
                    if (result.isEmpty()) {
                        callback.onError("提取失败")
                        return
                    }
                    callback.onSuccess(result)
                }

                override fun onFailure(call: retrofit2.Call<ChatApi.ChatResponse>, t: Throwable) {
                    callback.onError(t.message ?: "提取失败")
                }
            })
    }

    private fun buildMessages(
        history: List<Message>?,
        userMessage: String?,
        using: SessionChatOptions,
        providerId: String,
        apiHost: String?,
    ): List<ChatApi.ChatMessage> {
        val messages = ArrayList<ChatApi.ChatMessage>()
        if (using.systemPrompt != null && using.systemPrompt.trim().isNotEmpty()) {
            messages.add(ChatApi.ChatMessage("system", using.systemPrompt.trim()))
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
    private fun isStrictAlternationProvider(providerId: String?, apiHost: String?): Boolean {
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

    private fun streamChat(
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
                // 自动对话: 流式过滤 `<<<META...META>>>` 尾块, 防止用户气泡里闪现.
                // 仅在 autoChatEnabled 时启用; 关闭时是 no-op (process 直接返回原 chunk).
                val proactiveMetaFilter = if (using.autoChatEnabled)
                    com.example.aichat.chat.ProactiveMetaStreamFilter() else null
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
                                            // META filter 在 think 处理之后, 仅过滤 user-visible content.
                                            val visible = proactiveMetaFilter?.process(parts.content) ?: parts.content
                                            if (visible.isNotEmpty()) callback.onPartial(visible)
                                        }
                                        if (parts.reasoning.isNotEmpty()) {
                                            emittedInlineReasoning = true
                                            fullReasoning.append(parts.reasoning)
                                            callback.onReasoning(fullReasoning.toString())
                                        }
                                    } else {
                                        fullContent.append(contentDelta)
                                        val visible = proactiveMetaFilter?.process(contentDelta) ?: contentDelta
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
                        val visible = proactiveMetaFilter?.process(tail.content) ?: tail.content
                        if (visible.isNotEmpty()) callback.onPartial(visible)
                    }
                    if (tail.reasoning.isNotEmpty()) {
                        fullReasoning.append(tail.reasoning)
                        callback.onReasoning(fullReasoning.toString())
                    }
                }
                // 自动对话: 把 META filter 还没决断的尾巴吐出来 (没遇到 META 时可能保留 6 字节).
                proactiveMetaFilter?.flushTail()?.let { tail ->
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
                // 自动对话: 解析尾部 META 块, 把 cleanContent 交给上层. 模型未启用 / 没发 META
                // 时 ProactiveMetaParser.extract 返回 (raw.trimEnd, null), 行为与未引入 META 时一致.
                val rawFinal = fullContent.toString()
                val metaExtract = com.example.aichat.chat.ProactiveMetaParser.extract(rawFinal)
                callback.onProactiveMeta(metaExtract.meta)
                callback.onSuccess(metaExtract.cleanContent)
            }
        })
    }

    private fun fireCancelledOnce(callback: ChatCallback?, handle: ChatHandleImpl?) {
        if (callback == null || handle == null) return
        if (!handle.tryFireCancelled()) return
        callback.onCancelled()
    }

    private fun parseStopSequences(raw: String?): List<String>? {
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

    private fun resolveProviderId(selectedProviderId: String?, apiHost: String?): String {
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

    private fun shouldShowReasoning(options: SessionChatOptions?, providerId: String?, modelId: String?): Boolean {
        // Always show reasoning when the model returns it.
        // The request-side toggle (options.thinking) controls whether we ASK
        // the model to think, not whether we display reasoning it chose to emit.
        // Models like Qwen3 emit <think> tags even when not requested; SSE
        // reasoning_content fields should never be silently discarded.
        return true
    }

    private fun isIntrinsicReasoningModel(providerId: String?, modelId: String?): Boolean {
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

    private fun isLocalOpenAiCompatibleProvider(providerId: String?): Boolean {
        val pid = providerId?.trim()?.lowercase(java.util.Locale.ROOT) ?: ""
        if ("lmstudio" == pid) return true
        if ("ollama" == pid) return true
        return isLlamaProviderId(pid)
    }

    private fun isLlamaProviderId(pid: String?): Boolean {
        if (pid == null || pid.isEmpty()) return false
        return "llama" == pid
                || "llamacpp" == pid
                || "llama.cpp" == pid
                || "llama-cpp" == pid
    }

    private fun extractAssistantContent(body: ChatApi.ChatResponse): String {
        val choices = body.choices
        if (choices == null || choices.isEmpty()) return ""
        val first = choices[0] ?: return ""
        val message = first.message ?: return ""
        val content: JsonElement = message.content ?: return ""
        if (content.isJsonNull) return ""
        try {
            if (content.isJsonPrimitive) return content.asString
            if (content.isJsonArray) {
                val out = StringBuilder()
                val arr = content.asJsonArray
                for (one in arr) {
                    if (one == null || one.isJsonNull) continue
                    if (one.isJsonPrimitive) {
                        out.append(one.asString)
                        continue
                    }
                    if (!one.isJsonObject) continue
                    val obj = one.asJsonObject
                    val txt = firstNonEmpty(
                        getStringFlexible(obj, "text"),
                        getStringFlexible(obj, "content"),
                        getStringFlexible(obj, "value")
                    )
                    if (txt.isNotEmpty()) out.append(txt)
                }
                return out.toString()
            }
            if (content.isJsonObject) {
                val obj = content.asJsonObject
                return firstNonEmpty(
                    getStringFlexible(obj, "text"),
                    getStringFlexible(obj, "content"),
                    getStringFlexible(obj, "value")
                )
            }
        } catch (ignored: Exception) {}
        return ""
    }

    private fun stripThinkTags(text: String?): String {
        if (text == null || text.isEmpty()) return ""
        return text.replace(Regex("(?is)<think>.*?</think>"), "").trim()
    }

    private fun cleanTitleResult(raw: String?): String {
        var text = stripThinkTags(raw)
        text = text.replace("\r", "\n").trim()

        // Remove common verbose reasoning prefixes from uncensored/local models.
        text = text.replace(Regex("(?is)^\\s*(thinking\\s*process|reasoning|analysis|思考过程|分析过程)\\s*[:：].*$"), "")
        if (text.isEmpty()) return ""

        // Prefer first non-empty line that looks like a short Chinese title.
        val lines = text.split(Regex("\\n+"))
        var best = ""
        for (line in lines) {
            var one = line.trim()
            if (one.isEmpty()) continue
            one = one.replace(Regex("^[\\-\\*\\d\\.\\)\\(\\[\\]【】\\s]+"), "").trim()
            one = one.replace(Regex("[。！？，,.!?:：;；\"'\\u201C\\u201D\\u2018\\u2019（）()\\[\\]{}]"), "").trim()
            if (one.isEmpty()) continue
            if (one.matches(Regex(".*[\\u4e00-\\u9fa5].*")) && one.length >= 3 && one.length <= 12) {
                return one
            }
            if (best.isEmpty()) best = one
        }

        if (best.isNotEmpty()) {
            best = best.replace(Regex("[。！？，,.!?:：;；\"'\\u201C\\u201D\\u2018\\u2019（）()\\[\\]{}]"), "").trim()
            return best
        }
        return text.replace("\n", " ").replace(Regex("[。！？，,.!?:：;；\"'\\u201C\\u201D\\u2018\\u2019（）()\\[\\]{}]"), "").trim()
    }

    private fun extractTitleFromJsonOrText(raw: String?): String {
        val text = raw?.trim() ?: ""
        if (text.isEmpty()) return ""
        try {
            val jsonSlice = extractJsonObjectSlice(text)
            if (jsonSlice.isNotEmpty()) {
                val obj = JsonParser().parse(jsonSlice).asJsonObject
                val title = firstNonEmpty(
                    getStringFlexible(obj, "title"),
                    getStringFlexible(obj, "name"),
                    getStringFlexible(obj, "result")
                )
                if (title.trim().isNotEmpty()) return title.trim()
            }
        } catch (ignored: Exception) {}
        return text
    }

    private fun extractTextFieldFromJsonOrText(raw: String?, vararg preferredKeys: String): String {
        val text = raw?.trim() ?: ""
        if (text.isEmpty()) return ""
        try {
            val jsonSlice = extractJsonObjectSlice(text)
            if (jsonSlice.isNotEmpty()) {
                val obj = JsonParser().parse(jsonSlice).asJsonObject
                for (key in preferredKeys) {
                    val value = getStringFlexible(obj, key)
                    if (value.trim().isNotEmpty()) return value.trim()
                }
                val fallback = firstNonEmpty(
                    getStringFlexible(obj, "text"),
                    getStringFlexible(obj, "message"),
                    getStringFlexible(obj, "data")
                )
                if (fallback.trim().isNotEmpty()) return fallback.trim()
            }
        } catch (ignored: Exception) {}
        return text
    }

    private fun normalizeChapterPlanJson(source: JsonObject): JsonObject {
        val out = JsonObject()
        out.addProperty("chapterGoal", pickString(source, "chapterGoal", "chapter_goal", "goal", "章节目标", "本章目标", "目标"))
        out.addProperty("startState", pickString(source, "startState", "start_state", "起始状态", "开场状态", "开局状态"))
        out.addProperty("endState", pickString(source, "endState", "end_state", "结束状态", "结尾状态", "收束状态"))
        out.add("characterDrives", normalizeCharacterDrives(pickElement(source,
            "characterDrives", "character_drives", "characters", "角色驱动", "角色动机")))
        out.add("knowledgeBoundary", normalizeStringArray(pickElement(source,
            "knowledgeBoundary", "knowledge_boundary", "knowledge", "知情边界", "知情约束")))
        out.add("eventChain", normalizeStringArray(pickElement(source,
            "eventChain", "event_chain", "events", "事件链", "关键事件")))
        out.add("foreshadow", normalizeStringArray(pickElement(source,
            "foreshadow", "foreshadows", "伏笔")))
        out.add("payoff", normalizeStringArray(pickElement(source,
            "payoff", "payoffs", "回收")))
        out.add("forbidden", normalizeStringArray(pickElement(source,
            "forbidden", "forbiddenList", "禁写清单", "禁写", "禁忌")))
        out.addProperty("styleGuide", pickString(source, "styleGuide", "style_guide", "style", "writingStyle", "文风", "文风与节奏"))
        // Keep target length blank so user can decide it manually in dialog.
        out.addProperty("targetLength", "")
        return out
    }

    private fun pickElement(source: JsonObject?, vararg keys: String): JsonElement? {
        if (source == null) return null
        for (key in keys) {
            if (key.isEmpty()) continue
            val e = source.get(key)
            if (e != null && !e.isJsonNull) return e
        }
        return null
    }

    private fun pickString(source: JsonObject?, vararg keys: String): String {
        if (source == null) return ""
        for (key in keys) {
            val v = getStringFlexible(source, key)
            if (v.trim().isNotEmpty()) return v.trim()
        }
        return ""
    }

    private fun countNonEmptyPlanFields(plan: JsonObject?): Int {
        if (plan == null) return 0
        var count = 0
        if (getStringFlexible(plan, "chapterGoal").trim().isNotEmpty()) count++
        if (getStringFlexible(plan, "startState").trim().isNotEmpty()) count++
        if (getStringFlexible(plan, "endState").trim().isNotEmpty()) count++
        if (getStringFlexible(plan, "styleGuide").trim().isNotEmpty()) count++
        if (plan.has("characterDrives") && plan.get("characterDrives").isJsonArray
            && plan.getAsJsonArray("characterDrives").size() > 0) count++
        if (plan.has("knowledgeBoundary") && plan.get("knowledgeBoundary").isJsonArray
            && plan.getAsJsonArray("knowledgeBoundary").size() > 0) count++
        if (plan.has("eventChain") && plan.get("eventChain").isJsonArray
            && plan.getAsJsonArray("eventChain").size() > 0) count++
        if (plan.has("foreshadow") && plan.get("foreshadow").isJsonArray
            && plan.getAsJsonArray("foreshadow").size() > 0) count++
        if (plan.has("payoff") && plan.get("payoff").isJsonArray
            && plan.getAsJsonArray("payoff").size() > 0) count++
        if (plan.has("forbidden") && plan.get("forbidden").isJsonArray
            && plan.getAsJsonArray("forbidden").size() > 0) count++
        return count
    }

    private fun previewForLog(text: String?, maxLen: Int): String {
        val v = text?.replace("\n", "\\n")?.trim() ?: ""
        if (v.length <= Math.max(32, maxLen)) return v
        return v.substring(0, Math.max(32, maxLen)) + "..."
    }

    private fun normalizeStringArray(element: JsonElement?): JsonArray {
        val out = JsonArray()
        if (element == null || element.isJsonNull || !element.isJsonArray) return out
        val arr = element.asJsonArray
        for (i in 0 until arr.size()) {
            val one = arr.get(i)
            if (one == null || one.isJsonNull) continue
            if (one.isJsonPrimitive) out.add(one.asString)
            else if (one.isJsonObject) {
                val text = firstNonEmpty(
                    getStringFlexible(one.asJsonObject, "text"),
                    getStringFlexible(one.asJsonObject, "value"),
                    one.toString()
                )
                if (text.trim().isNotEmpty()) out.add(text.trim())
            } else {
                out.add(one.toString())
            }
        }
        return out
    }

    private fun normalizeCharacterDrives(element: JsonElement?): JsonArray {
        val out = JsonArray()
        if (element == null || element.isJsonNull || !element.isJsonArray) return out
        val arr = element.asJsonArray
        for (i in 0 until arr.size()) {
            val one = arr.get(i)
            if (one == null || one.isJsonNull) continue
            val item = JsonObject()
            if (one.isJsonObject) {
                val src = one.asJsonObject
                item.addProperty("name", getStringFlexible(src, "name"))
                item.addProperty("goal", getStringFlexible(src, "goal"))
                item.addProperty("misbelief", getStringFlexible(src, "misbelief"))
                item.addProperty("emotion", getStringFlexible(src, "emotion"))
            } else {
                val text = if (one.isJsonPrimitive) one.asString else one.toString()
                item.addProperty("name", "")
                item.addProperty("goal", text)
                item.addProperty("misbelief", "")
                item.addProperty("emotion", "")
            }
            out.add(item)
        }
        return out
    }

    private fun extractJsonObjectSlice(text: String?): String {
        if (text == null || text.isEmpty()) return ""
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return ""
        return text.substring(start, end + 1)
    }

    private fun buildNoThinkingReasoning(providerId: String, localOpenAiCompat: Boolean): JsonObject? {
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
         * Phase A2 contract: rows persisted via this callback MUST NOT be
         * pushed to the remote sync server — server schema does not yet
         * accept role=tool_call / tool_result. Implementations should leave
         * `turnId`/`assistantId` empty on the inserted row so
         * SyncQueueDrainer's `WHERE turnId != ''` filter skips it.
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
