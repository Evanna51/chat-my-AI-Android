package com.example.aichat;

import com.google.gson.JsonObject;
import java.util.List;

import com.google.gson.annotations.SerializedName;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Url;

public interface ChatApi {
    @POST("chat/completions")
    Call<ChatResponse> chat(
            @Header("Authorization") String auth,
            @Header("Content-Type") String contentType,
            @Body ChatRequest request
    );

    @POST
    Call<ChatResponse> chatWithUrl(
            @Url String url,
            @Header("Authorization") String auth,
            @Header("Content-Type") String contentType,
            @Body ChatRequest request
    );

    class ChatRequest {
        public String model;
        public List<ChatMessage> messages;
        public boolean stream = false;
        public Integer n;
        @SerializedName("max_tokens")
        public Integer maxTokens;
        public Double temperature;
        @SerializedName("top_p")
        public Double topP;
        public List<String> stop;
        public Boolean thinking;
        public JsonObject reasoning;
        @SerializedName("providerOptions")
        public JsonObject providerOptions;
    }

    class ChatMessage {
        public String role;
        public String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    class ChatResponse {
        public List<Choice> choices;
    }

    class Choice {
        public MessageDelta message;
    }

    class MessageDelta {
        public String content;
    }
}
