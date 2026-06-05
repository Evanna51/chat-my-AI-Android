package com.example.aichat.chat

import android.util.Log
import com.example.aichat.ApiUtils
import com.example.aichat.AiModelConfig
import com.example.aichat.ChatApi
import com.example.aichat.ChatService
import com.example.aichat.ModelConfig
import com.example.aichat.R
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 会话标题生成器：根据用户第一条消息生成一个 3–12 字的中文短标题。
 *
 * 走「话题命名」专用模型配置 ([AiModelConfig.getConfigForThreadNaming])，
 * 与主聊天模型解耦；同步 retrofit 请求（不走 streamChat），因为标题文本
 * 短、不需要流式回放。
 *
 * 从 ChatService 抽出（R4），ChatService 保留同名公开方法作为薄 forwarder，
 * 外部 caller (ChatViewModel) 无须改动。
 */
class ChatTitleGenerator(private val service: ChatService) {

    companion object {
        private const val TAG = "ChatTitleGenerator"
    }

    fun generate(firstUserMessage: String?, callback: ChatService.ChatCallback) {
        val context = service.context
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
        providerId = service.resolveProviderId(providerId, config.apiHost)
        Log.d(TAG, "generateThreadTitle model=${config.modelId}, host=${config.apiHost}, providerId=$providerId")

        var baseUrl = config.toRetrofitBaseUrl()
        if (!baseUrl.endsWith("/")) baseUrl += "/"

        val localOpenAiCompat = service.isLocalOpenAiCompatibleProvider(providerId)
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
        service.applyModelDefaultsToRequest(request, providerId, config.modelId)
        request.stop = null
        request.thinking = if (localOpenAiCompat) java.lang.Boolean.FALSE else null
        request.reasoning = service.buildNoThinkingReasoning(providerId, localOpenAiCompat)
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
                val raw = ChatTextHelpers.extractAssistantContent(body314)
                var title = ChatTextHelpers.extractTitleFromJsonOrText(raw)
                title = ChatTextHelpers.cleanTitleResult(title)
                if (title.length > 12) title = title.substring(0, 12)
                if (title.length < 3) title = if (source.length > 12) source.substring(0, 12) else source
                callback.onSuccess(title)
            }

            override fun onFailure(call: retrofit2.Call<ChatApi.ChatResponse>, t: Throwable) {
                callback.onError(t.message ?: "命名失败")
            }
        })
    }
}
