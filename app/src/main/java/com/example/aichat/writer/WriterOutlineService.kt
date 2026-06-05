package com.example.aichat.writer

import com.example.aichat.AiModelConfig
import com.example.aichat.ApiUtils
import com.example.aichat.ChatApi
import com.example.aichat.ChatService
import com.example.aichat.Message
import com.example.aichat.ModelConfig
import com.example.aichat.R
import com.example.aichat.chat.ChatTextHelpers
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Writer 模式的大纲生成 + 单条总结。
 * 从 ChatService 抽出（R4），通过持有 [ChatService] 引用调用其 internal 方法。
 */
class WriterOutlineService(private val service: ChatService) {

    companion object {
        private const val TAG = "WriterOutlineService"
    }

    fun generateSession(history: List<Message>?, outlinePrompt: String? = null, callback: ChatService.ChatCallback) {
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
            config = AiModelConfig(service.context).getConfigForSummary()
        } catch (e: Exception) {
            callback.onError(service.context.getString(R.string.error_config_parse_failed, ""))
            return
        }
        if (config == null || !config.isValid()) {
            callback.onError(service.context.getString(R.string.error_no_summary_model_selected))
            return
        }

        var providerId = ""
        val summaryPreset = ModelConfig(service.context).getSummaryPreset()
        if (summaryPreset != null && summaryPreset.contains(":")) {
            providerId = summaryPreset.substring(0, summaryPreset.indexOf(':'))
        }
        providerId = service.resolveProviderId(providerId, config.apiHost)

        var baseUrl = config.toRetrofitBaseUrl()
        if (!baseUrl.endsWith("/")) baseUrl += "/"

        val localOpenAiCompat = service.isLocalOpenAiCompatibleProvider(providerId)
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

        val styleGuide = outlinePrompt?.trim().orEmpty()
        val styleLine = if (styleGuide.isNotEmpty())
            "\n8) 文风与风格指导：$styleGuide" else ""

        val requestMessages = ArrayList<ChatApi.ChatMessage>()
        requestMessages.add(ChatApi.ChatMessage("system",
            "你是对话大纲助手。请根据输入对话生成“信息保真”的大纲正文（80到320字），宁可稍长也不要遗漏关键信息。\n" +
                    "仅输出一个JSON对象，不要任何额外文本。\n" +
                    "严格格式:{\"outline\":\"...\"}\n" +
                    "强约束:\n" +
                    "1) 输出必须以 { 开始、以 } 结束。\n" +
                    "2) 只允许一个键 outline，不要额外键。\n" +
                    "3) 不要Markdown代码块，不要解释，不要Thinking/Reasoning文本。\n" +
                    "4) outline 内容不要标题，不要列表。\n" +
                    "5) 必须保留关键细节：人物/对象名称、核心事件、动机或目标、约束条件、结果或当前进展。\n" +
                    "6) 若原文出现时间、地点、数字、专有名词、规则设定，优先保留，不要泛化改写。\n" +
                    "7) 避免空泛词（如“发生了一些事”“进行了讨论”），改为具体事实。" + styleLine))
        requestMessages.add(ChatApi.ChatMessage("user", prompt))

        val request = ChatApi.ChatRequest()
        request.model = config.modelId
        request.messages = requestMessages
        request.stream = false
        request.n = 1
        request.maxTokens = 620
        request.temperature = 0.2
        request.topP = 0.8
        service.applyModelDefaultsToRequest(request, providerId, config.modelId)
        request.stop = null
        request.thinking = if (localOpenAiCompat) java.lang.Boolean.FALSE else null
        request.reasoning = service.buildNoThinkingReasoning(providerId, localOpenAiCompat)
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
                    var outline = ChatTextHelpers.extractAssistantContent(body450)
                    outline = ChatTextHelpers.extractTextFieldFromJsonOrText(outline, "outline", "summary", "content", "result")
                    outline = ChatTextHelpers.stripThinkTags(outline).replace("\n", " ").trim()
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

    fun summarize(content: String?, outlinePrompt: String? = null, callback: ChatService.ChatCallback) {
        var source = content?.trim() ?: ""
        if (source.isEmpty()) {
            callback.onError(service.context.getString(R.string.error_message_empty))
            return
        }
        if (source.length > 2500) {
            source = source.substring(0, 2500)
        }
        val config: AiModelConfig.ResolvedConfig
        try {
            config = AiModelConfig(service.context).getConfigForSummary()
        } catch (e: Exception) {
            callback.onError(service.context.getString(R.string.error_config_parse_failed, ""))
            return
        }
        if (config == null || !config.isValid()) {
            callback.onError(service.context.getString(R.string.error_no_summary_model_selected))
            return
        }

        var providerId = ""
        val summaryPreset = ModelConfig(service.context).getSummaryPreset()
        if (summaryPreset != null && summaryPreset.contains(":")) {
            providerId = summaryPreset.substring(0, summaryPreset.indexOf(':'))
        }
        providerId = service.resolveProviderId(providerId, config.apiHost)

        var baseUrl = config.toRetrofitBaseUrl()
        if (!baseUrl.endsWith("/")) baseUrl += "/"

        val localOpenAiCompat = service.isLocalOpenAiCompatibleProvider(providerId)
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
        val styleGuide2 = outlinePrompt?.trim().orEmpty()
        val styleLine2 = if (styleGuide2.isNotEmpty())
            "\n8) 文风与风格指导：$styleGuide2" else ""

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
                    "7) 不要只写结论，需包含必要过程与因果关系。" + styleLine2))
        requestMessages.add(ChatApi.ChatMessage("user", source))

        val request = ChatApi.ChatRequest()
        request.model = config.modelId
        request.messages = requestMessages
        request.stream = false
        request.n = 1
        request.maxTokens = 520
        request.temperature = 0.2
        request.topP = 0.8
        service.applyModelDefaultsToRequest(request, providerId, config.modelId)
        request.stop = null
        request.thinking = if (localOpenAiCompat) java.lang.Boolean.FALSE else null
        request.reasoning = service.buildNoThinkingReasoning(providerId, localOpenAiCompat)
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
                    var summary = ChatTextHelpers.extractAssistantContent(body571)
                    summary = ChatTextHelpers.extractTextFieldFromJsonOrText(summary, "summary", "outline", "content", "result")
                    summary = ChatTextHelpers.stripThinkTags(summary).replace("\n", " ").trim()
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
}
