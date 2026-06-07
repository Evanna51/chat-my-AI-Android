package com.example.aichat.writer

import com.example.aichat.AiModelConfig
import com.example.aichat.ApiUtils
import com.example.aichat.ChatApi
import com.example.aichat.ChatService
import com.example.aichat.chat.ChatCallback
import com.example.aichat.ModelConfig
import com.example.aichat.chat.ChatTextHelpers
import com.example.aichat.prompts.Prompts
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Writer 模式的卷大纲生成 + 知情约束抽取。
 * 从 ChatService 抽出（R4），通过持有 [ChatService] 引用调用其 internal 方法。
 */
class WriterVolumeService(private val service: ChatService) {

    companion object {
        private const val TAG = "WriterVolumeService"
    }

    fun generateVolume(
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
            config = AiModelConfig(service.context).getConfigForSummary()
        } catch (e: Exception) {
            callback.onError(service.context.getString(com.example.aichat.R.string.error_config_parse_failed, ""))
            return
        }
        if (config == null || !config.isValid()) {
            callback.onError(service.context.getString(com.example.aichat.R.string.error_no_summary_model_selected))
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
        requestMessages.add(ChatApi.ChatMessage("system", Prompts.Writer.VolumeMerge.SYSTEM))
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
        service.applyModelDefaultsToRequest(request, providerId, config.modelId)
        request.stop = null
        request.thinking = if (localOpenAiCompat) java.lang.Boolean.FALSE else null
        request.reasoning = service.buildNoThinkingReasoning(providerId, localOpenAiCompat)
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
                    var result = ChatTextHelpers.extractAssistantContent(body)
                    result = ChatTextHelpers.stripThinkTags(result).trim()
                    if (result.isEmpty()) { callback.onError("卷纲生成失败"); return }
                    callback.onSuccess(result)
                }

                override fun onFailure(call: retrofit2.Call<ChatApi.ChatResponse>, t: Throwable) {
                    callback.onError(t.message ?: "卷纲生成失败")
                }
            })
    }

    fun extractKnowledge(outlineText: String?, callback: ChatCallback) {
        val outline = outlineText?.trim() ?: ""
        if (outline.isEmpty()) {
            callback.onError("大纲为空，无法提取知情约束")
            return
        }

        val config: AiModelConfig.ResolvedConfig
        try {
            config = AiModelConfig(service.context).getConfigForSummary()
        } catch (e: Exception) {
            callback.onError(service.context.getString(com.example.aichat.R.string.error_config_parse_failed, ""))
            return
        }
        if (config == null || !config.isValid()) {
            callback.onError(service.context.getString(com.example.aichat.R.string.error_no_summary_model_selected))
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
        requestMessages.add(ChatApi.ChatMessage("system", Prompts.Writer.KnowledgeBoundary.SYSTEM))
        requestMessages.add(ChatApi.ChatMessage("user", outline))

        val request = ChatApi.ChatRequest()
        request.model = config.modelId
        request.messages = requestMessages
        request.stream = false
        request.n = 1
        request.maxTokens = 1500
        request.temperature = 0.3
        request.topP = 0.8
        service.applyModelDefaultsToRequest(request, providerId, config.modelId)
        request.stop = null
        request.thinking = if (localOpenAiCompat) java.lang.Boolean.FALSE else null
        request.reasoning = service.buildNoThinkingReasoning(providerId, localOpenAiCompat)
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
                    var result = ChatTextHelpers.extractAssistantContent(body)
                    result = ChatTextHelpers.stripThinkTags(result).trim()
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
}
