package com.example.aichat

import android.content.Context
import android.util.Log
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
    }

    private val context: Context = context.applicationContext

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
    fun chat(history: List<Message>, userMessage: String, options: SessionChatOptions? = null, callback: ChatCallback): ChatHandle {
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

        val using = options ?: SessionChatOptions()
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

        val messages = buildMessages(history, userMessage, using)
        if (messages.isEmpty()) {
            callback.onError("消息内容为空")
            return handle
        }

        if (using.streamOutput) {
            streamChat(client, config, using, messages, callback, selectedProviderId, handle)
            return handle
        }

        val requestMessages = ArrayList(messages)
        val request = ChatApi.ChatRequest()
        request.model = config.modelId
        request.messages = requestMessages
        request.stream = false
        request.temperature = using.temperature.toDouble()
        request.topP = using.topP.toDouble()
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
                        val body = response.body()
                        if (body!!.choices != null && body.choices.isNotEmpty()) {
                            val choice = body.choices[0]
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
                if (!response.isSuccessful || response.body() == null || response.body()!!.choices == null
                    || response.body()!!.choices.isEmpty() || response.body()!!.choices[0] == null
                    || response.body()!!.choices[0].message == null) {
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
                val raw = extractAssistantContent(response.body()!!)
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
                    if (!response.isSuccessful || response.body() == null || response.body()!!.choices == null
                        || response.body()!!.choices.isEmpty() || response.body()!!.choices[0] == null
                        || response.body()!!.choices[0].message == null) {
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
                    var outline = extractAssistantContent(response.body()!!)
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
                    if (!response.isSuccessful || response.body() == null || response.body()!!.choices == null
                        || response.body()!!.choices.isEmpty() || response.body()!!.choices[0] == null
                        || response.body()!!.choices[0].message == null) {
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
                    var summary = extractAssistantContent(response.body()!!)
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

    fun generateChapterPlanJson(userInput: String?, storyContext: String?, callback: ChatCallback) {
        val input = userInput?.trim() ?: ""
        var contextText = storyContext?.trim() ?: ""
        if (input.isEmpty()) {
            callback.onError("输入为空，无法生成章节计划")
            return
        }
        if (contextText.length > 2800) {
            contextText = contextText.substring(0, 2800)
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
            "你是小说章节规划助手。请为\u201C本轮写作\u201D生成可执行计划，并严格输出 JSON 对象。\n" +
                    "仅输出一个JSON对象，不要任何额外文本。\n" +
                    "严格键集合:\n" +
                    "{\"chapterGoal\":\"\",\"startState\":\"\",\"endState\":\"\",\"characterDrives\":[],\"knowledgeBoundary\":[],\"eventChain\":[],\"foreshadow\":[],\"payoff\":[],\"forbidden\":[],\"styleGuide\":\"\"}\n" +
                    "约束:\n" +
                    "1) 输出必须以 { 开始、以 } 结束。\n" +
                    "2) 必须保留全部键，禁止新增或删除键。\n" +
                    "3) 除 characterDrives 外，数组元素都用字符串；characterDrives 用对象数组，结构为 {\"name\":\"\",\"goal\":\"\",\"misbelief\":\"\",\"emotion\":\"\"}。\n" +
                    "4) 内容具体可执行，避免空话。"))
        requestMessages.add(ChatApi.ChatMessage("user",
            "【用户本轮输入】\n" + input +
                    if (contextText.isEmpty()) "" else ("\n\n【当前写作上下文】\n" + contextText)))

        val request = ChatApi.ChatRequest()
        request.model = config.modelId
        request.messages = requestMessages
        request.stream = false
        request.n = 1
        request.maxTokens = 800
        request.temperature = 0.15
        request.topP = 0.6
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
                    if (!response.isSuccessful || response.body() == null || response.body()!!.choices == null
                        || response.body()!!.choices.isEmpty() || response.body()!!.choices[0] == null
                        || response.body()!!.choices[0].message == null) {
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
                    val raw = extractAssistantContent(response.body()!!)
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

    fun auditNovelLeakage(knowledgeConstraints: String?, assistantContent: String?, callback: ChatCallback) {
        val constraints = knowledgeConstraints?.trim() ?: ""
        var aiText = assistantContent?.trim() ?: ""
        if (constraints.isEmpty()) {
            callback.onError("知情约束为空")
            return
        }
        if (aiText.isEmpty()) {
            callback.onError("待审计内容为空")
            return
        }
        if (aiText.length > 4000) {
            aiText = aiText.substring(0, 4000)
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
            "你是小说写作审计员。请依据\u201C知情约束\u201D检查文本是否存在角色越权知情（泄密）问题。\n" +
                    "仅输出一个JSON对象，不要任何额外文本。\n" +
                    "严格格式:{\"report\":\"结论：通过/不通过\\n风险点：...\\n修复建议：...\"}\n" +
                    "强约束:\n" +
                    "1) 输出必须以 { 开始、以 } 结束。\n" +
                    "2) 只允许一个键 report，不要额外键。\n" +
                    "3) 不要Markdown代码块，不要解释，不要Thinking/Reasoning文本。\n" +
                    "4) 风险点逐条列出（若无写\u201C无\u201D），修复建议需可执行（若通过可写\u201C保持当前写法\u201D）。"))
        requestMessages.add(ChatApi.ChatMessage("user",
            "【知情约束】\n" + constraints + "\n\n【待审计文本】\n" + aiText))

        val request = ChatApi.ChatRequest()
        request.model = config.modelId
        request.messages = requestMessages
        request.stream = false
        request.n = 1
        request.maxTokens = 520
        request.temperature = 0.1
        request.topP = 0.8
        request.stop = null
        request.thinking = if (localOpenAiCompat) java.lang.Boolean.FALSE else null
        request.reasoning = buildNoThinkingReasoning(providerId, localOpenAiCompat)
        if (!localOpenAiCompat) {
            val auditResponseFormat = JsonObject()
            auditResponseFormat.addProperty("type", "json_object")
            request.responseFormat = auditResponseFormat
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
                    if (!response.isSuccessful || response.body() == null || response.body()!!.choices == null
                        || response.body()!!.choices.isEmpty() || response.body()!!.choices[0] == null
                        || response.body()!!.choices[0].message == null) {
                        var detail = ""
                        try {
                            if (response.errorBody() != null) {
                                detail = response.errorBody()!!.string()
                            }
                        } catch (ignored: Exception) {}
                        callback.onError("审计失败: " + response.code()
                                + if (detail.isEmpty()) "" else ("\n" + detail))
                        return
                    }
                    var report = extractAssistantContent(response.body()!!)
                    report = extractTextFieldFromJsonOrText(report, "report", "summary", "content", "result")
                    report = stripThinkTags(report).trim()
                    if (report.isEmpty()) {
                        callback.onError("审计失败")
                        return
                    }
                    callback.onSuccess(report)
                }

                override fun onFailure(call: retrofit2.Call<ChatApi.ChatResponse>, t: Throwable) {
                    callback.onError(t.message ?: "审计失败")
                }
            })
    }

    private fun buildMessages(history: List<Message>?, userMessage: String?, using: SessionChatOptions): List<ChatApi.ChatMessage> {
        val messages = ArrayList<ChatApi.ChatMessage>()
        if (using.systemPrompt != null && using.systemPrompt.trim().isNotEmpty()) {
            messages.add(ChatApi.ChatMessage("system", using.systemPrompt.trim()))
        }
        val source = history ?: ArrayList()
        val limit = using.contextMessageCount
        var start = 0
        if (limit >= 0 && source.size > limit) start = source.size - limit
        for (i in start until source.size) {
            val m = source[i] ?: continue
            val role = if (m.role == Message.ROLE_USER) "user" else "assistant"
            messages.add(ChatApi.ChatMessage(role, m.content ?: ""))
        }
        messages.add(ChatApi.ChatMessage("user", userMessage ?: ""))
        return messages
    }

    private fun streamChat(
        client: OkHttpClient,
        config: AiModelConfig.ResolvedConfig,
        using: SessionChatOptions,
        messages: List<ChatApi.ChatMessage>,
        callback: ChatCallback,
        providerId: String,
        handle: ChatHandleImpl
    ) {
        val chatUrl = ApiUtils.toBaseUrl(config.apiHost, config.apiPath)
        val request = JsonObject()
        request.addProperty("model", config.modelId)
        request.addProperty("stream", true)
        request.addProperty("temperature", using.temperature)
        request.addProperty("top_p", using.topP)
        val arr = JsonArray()
        for (m in messages) {
            val one = JsonObject()
            one.addProperty("role", m.role)
            one.addProperty("content", m.content)
            arr.add(one)
        }
        request.add("messages", arr)
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
        val shouldShowReasoning = shouldShowReasoning(using, providerId, config.modelId)
        Log.d(TAG, "stream request providerId=$providerId"
                + ", model=${config.modelId}"
                + ", thinking=${using.thinking}"
                + ", showReasoning=$shouldShowReasoning"
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
                val normalizeInlineThink = shouldNormalizeInlineThink(providerId)
                var promptTokens = 0
                var completionTokens = 0
                var totalTokens = 0
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
                                        val parts = splitInlineThink(contentDelta, inlineThinkState, false)
                                        if (parts.content.isNotEmpty()) {
                                            fullContent.append(parts.content)
                                            callback.onPartial(parts.content)
                                        }
                                        if (parts.reasoning.isNotEmpty()) {
                                            emittedInlineReasoning = true
                                            if (shouldShowReasoning) {
                                                fullReasoning.append(parts.reasoning)
                                                callback.onReasoning(fullReasoning.toString())
                                            }
                                        }
                                    } else {
                                        fullContent.append(contentDelta)
                                        callback.onPartial(contentDelta)
                                    }
                                }
                                val reasoningDelta = extractReasoningDelta(obj, first, delta)
                                if (shouldShowReasoning && reasoningDelta.isNotEmpty() && !emittedInlineReasoning) {
                                    fullReasoning.append(reasoningDelta)
                                    callback.onReasoning(fullReasoning.toString())
                                }
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
                    val tail = splitInlineThink("", inlineThinkState, true)
                    if (tail.content.isNotEmpty()) {
                        fullContent.append(tail.content)
                        callback.onPartial(tail.content)
                    }
                    if (shouldShowReasoning && tail.reasoning.isNotEmpty()) {
                        fullReasoning.append(tail.reasoning)
                        callback.onReasoning(fullReasoning.toString())
                    }
                }
                if (handle.isCancelled()) {
                    fireCancelledOnce(callback, handle)
                    return
                }
                callback.onUsage(promptTokens, completionTokens, totalTokens, System.currentTimeMillis() - start)
                callback.onSuccess(fullContent.toString())
            }
        })
    }

    private fun fireCancelledOnce(callback: ChatCallback?, handle: ChatHandleImpl?) {
        if (callback == null || handle == null) return
        if (!handle.tryFireCancelled()) return
        callback.onCancelled()
    }

    private fun getInt(obj: JsonObject, key: String): Int {
        return try {
            val e = obj.get(key)
            if (e == null || e.isJsonNull) 0 else e.asInt
        } catch (ex: Exception) {
            0
        }
    }

    private fun getString(obj: JsonObject, key: String): String {
        return try {
            val e = obj.get(key)
            if (e == null || e.isJsonNull) "" else e.asString
        } catch (ex: Exception) {
            ""
        }
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
        if (isIntrinsicReasoningModel(providerId, modelId)) return true
        return options != null && options.thinking
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

    private fun extractReasoningDelta(root: JsonObject, choice: JsonObject?, delta: JsonObject?): String {
        var v = firstNonEmpty(
            getStringFlexible(delta, "reasoning_content"),
            getStringFlexible(delta, "reasoning"),
            getStringFlexible(delta, "thinking")
        )
        if (v.isNotEmpty()) return v

        val messageObj = if (choice != null && choice.has("message") && choice.get("message").isJsonObject)
            choice.getAsJsonObject("message") else null
        v = firstNonEmpty(
            getStringFlexible(choice, "reasoning_content"),
            getStringFlexible(choice, "reasoning"),
            getStringFlexible(choice, "thinking"),
            getStringFlexible(messageObj, "reasoning_content"),
            getStringFlexible(messageObj, "reasoning"),
            getStringFlexible(messageObj, "thinking"),
            getStringFlexible(root, "reasoning_content"),
            getStringFlexible(root, "reasoning"),
            getStringFlexible(root, "thinking")
        )
        return v
    }

    private fun getStringFlexible(obj: JsonObject?, key: String?): String {
        return try {
            if (obj == null || key == null || key.isEmpty()) return ""
            val e = obj.get(key) ?: return ""
            if (e.isJsonNull) return ""
            if (e.isJsonPrimitive) return e.asString
            // Some gateways return reasoning as array/object chunks; keep textual representation.
            e.toString()
        } catch (ex: Exception) {
            ""
        }
    }

    private fun firstNonEmpty(vararg values: String?): String {
        for (one in values) {
            if (one != null && one.isNotEmpty()) return one
        }
        return ""
    }

    private fun shouldNormalizeInlineThink(providerId: String?): Boolean {
        val pid = providerId?.trim()?.lowercase(java.util.Locale.ROOT) ?: ""
        return "lmstudio" == pid || "ollama" == pid || isLlamaProviderId(pid)
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
        if (body.choices == null || body.choices.isEmpty()) return ""
        val first = body.choices[0] ?: return ""
        if (first.message == null) return ""
        val content: JsonElement = first.message.content ?: return ""
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

    private class InlineThinkState {
        var inThink: Boolean = false
        var carry: String = ""
    }

    private class ContentReasoningParts(content: String?, reasoning: String?) {
        val content: String = content ?: ""
        val reasoning: String = reasoning ?: ""
    }

    private fun splitInlineThink(delta: String?, state: InlineThinkState?, flushTail: Boolean): ContentReasoningParts {
        if (state == null) {
            return ContentReasoningParts(delta ?: "", "")
        }
        val chunk = delta ?: ""
        val input = state.carry + chunk
        state.carry = ""
        if (input.isEmpty()) return ContentReasoningParts("", "")

        val carryLen = if (flushTail) 0 else computeThinkTagCarry(input)
        val parse = input.substring(0, input.length - carryLen)
        if (!flushTail && carryLen > 0) {
            state.carry = input.substring(input.length - carryLen)
        }

        val outContent = StringBuilder()
        val outReasoning = StringBuilder()
        var i = 0
        val openTag = "<think>"
        val closeTag = "</think>"
        while (i < parse.length) {
            if (state.inThink) {
                val close = indexOfIgnoreCase(parse, closeTag, i)
                if (close < 0) {
                    outReasoning.append(parse.substring(i))
                    i = parse.length
                } else {
                    outReasoning.append(parse, i, close)
                    i = close + closeTag.length
                    state.inThink = false
                }
            } else {
                val open = indexOfIgnoreCase(parse, openTag, i)
                if (open < 0) {
                    outContent.append(parse.substring(i))
                    i = parse.length
                } else {
                    outContent.append(parse, i, open)
                    i = open + openTag.length
                    state.inThink = true
                }
            }
        }

        if (flushTail && state.carry.isNotEmpty()) {
            if (state.inThink) outReasoning.append(state.carry)
            else outContent.append(state.carry)
            state.carry = ""
        }
        return ContentReasoningParts(outContent.toString(), outReasoning.toString())
    }

    private fun computeThinkTagCarry(input: String?): Int {
        if (input == null || input.isEmpty()) return 0
        val lower = input.lowercase(java.util.Locale.ROOT)
        val tags = arrayOf("<think>", "</think>")
        var best = 0
        for (tag in tags) {
            for (len in 1 until tag.length) {
                if (lower.endsWith(tag.substring(0, len))) {
                    if (len > best) best = len
                }
            }
        }
        return best
    }

    private fun indexOfIgnoreCase(text: String?, needle: String?, fromIndex: Int): Int {
        if (text == null || needle == null) return -1
        val lowerText = text.lowercase(java.util.Locale.ROOT)
        val lowerNeedle = needle.lowercase(java.util.Locale.ROOT)
        return lowerText.indexOf(lowerNeedle, Math.max(0, fromIndex))
    }

    interface ChatCallback {
        fun onSuccess(content: String)
        fun onError(message: String)
        fun onCancelled() {}
        fun onPartial(delta: String) {}
        fun onReasoning(reasoning: String) {}
        fun onUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int, elapsedMs: Long) {}
    }
}
