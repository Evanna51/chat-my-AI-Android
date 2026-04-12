package com.example.aichat

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

interface ChatApi {
    @POST("chat/completions")
    fun chat(
        @Header("Authorization") auth: String?,
        @Header("Content-Type") contentType: String,
        @Body request: ChatRequest
    ): Call<ChatResponse>

    @POST
    fun chatWithUrl(
        @Url url: String,
        @Header("Authorization") auth: String?,
        @Header("Content-Type") contentType: String,
        @Body request: ChatRequest
    ): Call<ChatResponse>

    class ChatRequest {
        @JvmField var model: String? = null
        @JvmField var messages: List<ChatMessage>? = null
        @JvmField var stream: Boolean = false
        @JvmField var n: Int? = null
        @SerializedName("max_tokens")
        @JvmField var maxTokens: Int? = null
        @JvmField var temperature: Double? = null
        @SerializedName("top_p")
        @JvmField var topP: Double? = null
        @JvmField var stop: List<String>? = null
        @JvmField var thinking: Boolean? = null
        @JvmField var reasoning: JsonObject? = null
        @SerializedName("response_format")
        @JvmField var responseFormat: JsonObject? = null
        @SerializedName("providerOptions")
        @JvmField var providerOptions: JsonObject? = null
    }

    class ChatMessage(
        @JvmField var role: String,
        @JvmField var content: String
    )

    class ChatResponse {
        @JvmField var choices: List<Choice>? = null
    }

    class Choice {
        @JvmField var message: MessageDelta? = null
    }

    class MessageDelta {
        @JvmField var content: JsonElement? = null
    }
}
