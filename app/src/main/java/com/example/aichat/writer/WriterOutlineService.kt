package com.example.aichat.writer

import com.example.aichat.AiModelConfig
import com.example.aichat.ApiUtils
import com.example.aichat.ChatApi
import com.example.aichat.ChatService
import com.example.aichat.chat.ChatCallback
import com.example.aichat.Message
import com.example.aichat.ModelConfig
import com.example.aichat.R
import com.example.aichat.chat.ChatTextHelpers
import com.example.aichat.prompts.Prompts
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

    fun generateSession(history: List<Message>?, outlinePrompt: String? = null, callback: ChatCallback) {
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

        val requestMessages = ArrayList<ChatApi.ChatMessage>()
        requestMessages.add(ChatApi.ChatMessage("system",
            Prompts.Writer.DialogueOutline.system(outlinePrompt.orEmpty())))
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

    fun summarize(content: String?, outlinePrompt: String? = null, callback: ChatCallback) {
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
        requestMessages.add(ChatApi.ChatMessage("system",
            Prompts.Writer.NovelSummary.system(outlinePrompt.orEmpty())))
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
