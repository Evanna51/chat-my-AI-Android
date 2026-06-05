package com.example.aichat.writer

import com.example.aichat.AiModelConfig
import com.example.aichat.ApiUtils
import com.example.aichat.ChatApi
import com.example.aichat.ChatService
import com.example.aichat.chat.ChatCallback
import com.example.aichat.ModelConfig
import com.example.aichat.chat.ChatTextHelpers
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
