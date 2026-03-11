package com.example.aichat;

import android.content.Context;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * 聊天服务，从 AiModelConfig 读取当前配置进行请求。
 */
public class ChatService {
    private static final String TAG = "ChatService";
    private final Context context;

    public interface ChatHandle {
        void cancel();
        boolean isCancelled();
    }

    private static class ChatHandleImpl implements ChatHandle {
        private volatile boolean cancelled;
        private volatile boolean cancelledCallbackFired;
        private volatile retrofit2.Call<?> retrofitCall;
        private volatile okhttp3.Call okHttpCall;

        @Override
        public void cancel() {
            cancelled = true;
            retrofit2.Call<?> callA = retrofitCall;
            if (callA != null) {
                try {
                    callA.cancel();
                } catch (Exception ignored) {}
            }
            okhttp3.Call callB = okHttpCall;
            if (callB != null) {
                try {
                    callB.cancel();
                } catch (Exception ignored) {}
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        void bindRetrofitCall(retrofit2.Call<?> call) {
            this.retrofitCall = call;
            if (cancelled && call != null) {
                try {
                    call.cancel();
                } catch (Exception ignored) {}
            }
        }

        void bindOkHttpCall(okhttp3.Call call) {
            this.okHttpCall = call;
            if (cancelled && call != null) {
                try {
                    call.cancel();
                } catch (Exception ignored) {}
            }
        }

        boolean tryFireCancelled() {
            if (cancelledCallbackFired) return false;
            cancelledCallbackFired = true;
            return true;
        }
    }

    public ChatService(Context context) {
        this.context = context.getApplicationContext();
    }

    public ChatHandle chat(List<Message> history, String userMessage, ChatCallback callback) {
        return chat(history, userMessage, null, callback);
    }

    public ChatHandle chat(List<Message> history, String userMessage, SessionChatOptions options, ChatCallback callback) {
        ChatHandleImpl handle = new ChatHandleImpl();
        if (callback == null) return handle;
        AiModelConfig.ResolvedConfig config;
        try {
            config = new AiModelConfig(context).getConfigForChat();
        } catch (Exception e) {
            callback.onError("配置解析失败: " + (e != null ? e.getMessage() : ""));
            return handle;
        }
        if (config == null || !config.isValid()) {
            callback.onError("请先在「模型配置」中选用对话模型");
            return handle;
        }

        SessionChatOptions using = options != null ? options : new SessionChatOptions();
        String selectedProviderId = "";
        if (using.modelKey != null && using.modelKey.contains(":")) {
            try {
                ConfiguredModelPicker.Option selected = ConfiguredModelPicker.Option.fromStorageKey(using.modelKey, context);
                if (selected != null) {
                    selectedProviderId = selected.providerId != null ? selected.providerId : "";
                    ProviderInfo p = new ProviderManager(context).getProvider(selected.providerId);
                    if (p != null) {
                        config.apiHost = p.apiHost;
                        config.apiPath = p.apiPath;
                        config.apiKey = p.apiKey;
                    }
                    if (selected.modelId != null && !selected.modelId.isEmpty()) {
                        config.modelId = selected.modelId;
                    }
                }
            } catch (Exception ignored) {}
        }

        selectedProviderId = resolveProviderId(selectedProviderId, config.apiHost);
        String baseUrl = config.toRetrofitBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            callback.onError("API 地址配置无效");
            return handle;
        }

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();

        if (!baseUrl.endsWith("/")) baseUrl += "/";

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        ChatApi api = retrofit.create(ChatApi.class);

        List<ChatApi.ChatMessage> messages = buildMessages(history, userMessage, using);
        if (messages.isEmpty()) {
            callback.onError("消息内容为空");
            return handle;
        }

        if (using.streamOutput) {
            streamChat(client, config, using, messages, callback, selectedProviderId, handle);
            return handle;
        }

        List<ChatApi.ChatMessage> requestMessages = new ArrayList<>(messages);
        ChatApi.ChatRequest request = new ChatApi.ChatRequest();
        request.model = config.modelId;
        request.messages = requestMessages;
        request.stream = false; // client currently parses non-stream responses
        request.temperature = (double) using.temperature;
        request.topP = (double) using.topP;
        request.stop = parseStopSequences(using.stop);
        request.thinking = null;
        request.reasoning = ProviderRequestOptionsBuilder.buildReasoningConfig(selectedProviderId, using);
        request.providerOptions = ProviderRequestOptionsBuilder.buildProviderOptions(selectedProviderId, using);
        Log.d(TAG, "chat request providerId=" + selectedProviderId
                + ", model=" + config.modelId
                + ", thinking=" + using.thinking
                + ", stopCount=" + (request.stop != null ? request.stop.size() : 0)
                + ", reasoning=" + (request.reasoning != null ? request.reasoning.toString() : "null")
                + ", providerOptions=" + (request.providerOptions != null ? request.providerOptions.toString() : "null"));

        String auth = (config.apiKey != null && !config.apiKey.trim().isEmpty())
                ? ("Bearer " + config.apiKey.trim()) : null;
        String chatUrl = ApiUtils.toBaseUrl(config.apiHost, config.apiPath);

        long start = System.currentTimeMillis();
        retrofit2.Call<ChatApi.ChatResponse> call = api.chatWithUrl(chatUrl, auth, "application/json", request);
        handle.bindRetrofitCall(call);
        call.enqueue(new retrofit2.Callback<ChatApi.ChatResponse>() {
            @Override
            public void onResponse(retrofit2.Call<ChatApi.ChatResponse> call,
                                   retrofit2.Response<ChatApi.ChatResponse> response) {
                if (handle.isCancelled()) {
                    fireCancelledOnce(callback, handle);
                    return;
                }
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        ChatApi.ChatResponse body = response.body();
                        if (body.choices != null && !body.choices.isEmpty()) {
                            ChatApi.Choice choice = body.choices.get(0);
                            if (choice != null && choice.message != null) {
                                String content = choice.message.content;
                                callback.onUsage(0, 0, 0, System.currentTimeMillis() - start);
                                callback.onSuccess(content != null ? content : "");
                                return;
                            }
                        }
                    }
                    String detail = "";
                    try {
                        if (response != null && response.errorBody() != null) {
                            detail = response.errorBody().string();
                        }
                    } catch (Exception ignored) {}
                    callback.onError("请求失败: "
                            + (response != null ? response.code() : "无响应")
                            + "\nURL: " + chatUrl
                            + (detail.isEmpty() ? "" : ("\n" + detail)));
                } catch (Exception e) {
                    callback.onError("解析响应失败: " + (e != null ? e.getMessage() : ""));
                }
            }

            @Override
            public void onFailure(retrofit2.Call<ChatApi.ChatResponse> call, Throwable t) {
                if (handle.isCancelled() || (call != null && call.isCanceled())) {
                    fireCancelledOnce(callback, handle);
                    return;
                }
                callback.onError(t != null ? t.getMessage() : "未知错误");
            }
        });
        return handle;
    }

    public void generateThreadTitle(String firstUserMessage, ChatCallback callback) {
        if (callback == null) return;
        String source = firstUserMessage != null ? firstUserMessage.trim() : "";
        if (source.isEmpty()) {
            callback.onError("消息为空");
            return;
        }
        AiModelConfig.ResolvedConfig config;
        try {
            config = new AiModelConfig(context).getConfigForThreadNaming();
        } catch (Exception e) {
            callback.onError("配置解析失败");
            return;
        }
        if (config == null || !config.isValid()) {
            callback.onError("请先在「模型配置」中选用话题命名模型");
            return;
        }

        String providerId = "";
        String threadNamingPreset = new ModelConfig(context).getThreadNamingPreset();
        if (threadNamingPreset != null && threadNamingPreset.contains(":")) {
            providerId = threadNamingPreset.substring(0, threadNamingPreset.indexOf(':'));
        }
        providerId = resolveProviderId(providerId, config.apiHost);
        Log.d(TAG, "generateThreadTitle model=" + config.modelId + ", host=" + config.apiHost + ", providerId=" + providerId);

        String baseUrl = config.toRetrofitBaseUrl();
        if (!baseUrl.endsWith("/")) baseUrl += "/";

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        ChatApi api = retrofit.create(ChatApi.class);

        List<ChatApi.ChatMessage> requestMessages = new ArrayList<>();
        requestMessages.add(new ChatApi.ChatMessage("system", "你是标题助手。请仅输出一个中文标题，长度3到12个字，不要标点，不要解释。"));
        requestMessages.add(new ChatApi.ChatMessage("user", source));

        ChatApi.ChatRequest request = new ChatApi.ChatRequest();
        request.model = config.modelId;
        request.messages = requestMessages;
        request.stream = false;
        request.n = 1;
        request.maxTokens = 24;
        request.temperature = 0.1;
        request.topP = 0.7;
        request.stop = new ArrayList<>();
        request.stop.add("\n");
        request.thinking = false;
        SessionChatOptions namingOptions = new SessionChatOptions();
        namingOptions.thinking = false;
        namingOptions.streamOutput = false;
        request.reasoning = ProviderRequestOptionsBuilder.buildReasoningConfig(providerId, namingOptions);
        request.providerOptions = null;

        String auth = (config.apiKey != null && !config.apiKey.trim().isEmpty())
                ? ("Bearer " + config.apiKey.trim()) : null;
        String chatUrl = ApiUtils.toBaseUrl(config.apiHost, config.apiPath);
        Log.d(TAG, "generateThreadTitle url=" + chatUrl
                + ", promptLen=" + source.length()
                + ", maxTokens=" + request.maxTokens
                + ", thinking=" + request.thinking
                + ", reasoning=" + (request.reasoning != null ? request.reasoning.toString() : "null"));
        api.chatWithUrl(chatUrl, auth, "application/json", request).enqueue(new retrofit2.Callback<ChatApi.ChatResponse>() {
            @Override
            public void onResponse(retrofit2.Call<ChatApi.ChatResponse> call, retrofit2.Response<ChatApi.ChatResponse> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().choices == null
                        || response.body().choices.isEmpty() || response.body().choices.get(0) == null
                        || response.body().choices.get(0).message == null) {
                    String detail = "";
                    try {
                        if (response != null && response.errorBody() != null) {
                            detail = response.errorBody().string();
                        }
                    } catch (Exception ignored) {}
                    callback.onError("命名失败: " + (response != null ? response.code() : "无响应")
                            + (detail.isEmpty() ? "" : ("\n" + detail)));
                    return;
                }
                String title = response.body().choices.get(0).message.content;
                if (title == null) {
                    callback.onError("命名失败");
                    return;
                }
                title = title.replace("\n", " ").trim();
                title = title.replaceAll("[。！？，,.!?:：;；\"'“”‘’（）()\\[\\]{}]", "");
                if (title.length() > 12) title = title.substring(0, 12);
                if (title.length() < 3) title = source.length() > 12 ? source.substring(0, 12) : source;
                callback.onSuccess(title);
            }

            @Override
            public void onFailure(retrofit2.Call<ChatApi.ChatResponse> call, Throwable t) {
                callback.onError(t != null ? t.getMessage() : "命名失败");
            }
        });
    }

    public void generateSessionOutline(List<Message> history, ChatCallback callback) {
        if (callback == null) return;
        List<Message> source = history != null ? history : new ArrayList<>();
        if (source.isEmpty()) {
            callback.onError("暂无可总结内容");
            return;
        }
        StringBuilder transcript = new StringBuilder();
        int max = Math.min(10, source.size());
        for (int i = 0; i < max; i++) {
            Message m = source.get(i);
            if (m == null) continue;
            String role = m.role == Message.ROLE_USER ? "用户" : "助手";
            String content = m.content != null ? m.content.trim() : "";
            if (content.isEmpty()) continue;
            if (content.length() > 200) content = content.substring(0, 200) + "...";
            transcript.append(role).append("：").append(content).append("\n");
        }
        String prompt = transcript.toString().trim();
        if (prompt.isEmpty()) {
            callback.onError("暂无可总结内容");
            return;
        }

        AiModelConfig.ResolvedConfig config;
        try {
            config = new AiModelConfig(context).getConfigForSummary();
        } catch (Exception e) {
            callback.onError("配置解析失败");
            return;
        }
        if (config == null || !config.isValid()) {
            callback.onError("请先在「模型配置」中选用总结模型");
            return;
        }

        String baseUrl = config.toRetrofitBaseUrl();
        if (!baseUrl.endsWith("/")) baseUrl += "/";

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        ChatApi api = retrofit.create(ChatApi.class);

        List<ChatApi.ChatMessage> requestMessages = new ArrayList<>();
        requestMessages.add(new ChatApi.ChatMessage("system",
                "你是对话大纲助手。请根据输入对话的长度，输出一段精简大纲（20到200字）。只输出正文，不要标题，不要列表。"));
        requestMessages.add(new ChatApi.ChatMessage("user", prompt));

        ChatApi.ChatRequest request = new ChatApi.ChatRequest();
        request.model = config.modelId;
        request.messages = requestMessages;
        request.stream = false;
        // Keep summary request minimal for best provider compatibility.
        request.n = null;
        request.maxTokens = null;
        request.temperature = null;
        request.topP = null;
        request.stop = null;
        request.thinking = null;
        request.reasoning = null;
        request.providerOptions = null;

        String auth = (config.apiKey != null && !config.apiKey.trim().isEmpty())
                ? ("Bearer " + config.apiKey.trim()) : null;
        String chatUrl = ApiUtils.toBaseUrl(config.apiHost, config.apiPath);
        api.chatWithUrl(chatUrl, auth, "application/json", request)
                .enqueue(new retrofit2.Callback<ChatApi.ChatResponse>() {
                    @Override
                    public void onResponse(retrofit2.Call<ChatApi.ChatResponse> call, retrofit2.Response<ChatApi.ChatResponse> response) {
                        if (!response.isSuccessful() || response.body() == null || response.body().choices == null
                                || response.body().choices.isEmpty() || response.body().choices.get(0) == null
                                || response.body().choices.get(0).message == null) {
                            String detail = "";
                            try {
                                if (response != null && response.errorBody() != null) {
                                    detail = response.errorBody().string();
                                }
                            } catch (Exception ignored) {}
                            callback.onError("生成大纲失败: " + (response != null ? response.code() : "无响应")
                                    + (detail.isEmpty() ? "" : ("\n" + detail)));
                            return;
                        }
                        String outline = response.body().choices.get(0).message.content;
                        outline = outline != null ? outline.replace("\n", " ").trim() : "";
                        if (outline.isEmpty()) {
                            callback.onError("生成大纲失败");
                            return;
                        }
                        callback.onSuccess(outline);
                    }

                    @Override
                    public void onFailure(retrofit2.Call<ChatApi.ChatResponse> call, Throwable t) {
                        callback.onError(t != null ? t.getMessage() : "生成大纲失败");
                    }
                });
    }

    private List<ChatApi.ChatMessage> buildMessages(List<Message> history, String userMessage, SessionChatOptions using) {
        List<ChatApi.ChatMessage> messages = new ArrayList<>();
        if (using.systemPrompt != null && !using.systemPrompt.trim().isEmpty()) {
            messages.add(new ChatApi.ChatMessage("system", using.systemPrompt.trim()));
        }
        List<Message> source = history != null ? history : new ArrayList<>();
        int limit = using.contextMessageCount;
        int start = 0;
        if (limit >= 0 && source.size() > limit) start = source.size() - limit;
        for (int i = start; i < source.size(); i++) {
            Message m = source.get(i);
            if (m == null) continue;
            String role = m.role == Message.ROLE_USER ? "user" : "assistant";
            messages.add(new ChatApi.ChatMessage(role, m.content != null ? m.content : ""));
        }
        messages.add(new ChatApi.ChatMessage("user", userMessage != null ? userMessage : ""));
        return messages;
    }

    private void streamChat(OkHttpClient client,
                            AiModelConfig.ResolvedConfig config,
                            SessionChatOptions using,
                            List<ChatApi.ChatMessage> messages,
                            ChatCallback callback,
                            String providerId,
                            ChatHandleImpl handle) {
        String chatUrl = ApiUtils.toBaseUrl(config.apiHost, config.apiPath);
        JsonObject request = new JsonObject();
        request.addProperty("model", config.modelId);
        request.addProperty("stream", true);
        request.addProperty("temperature", using.temperature);
        request.addProperty("top_p", using.topP);
        JsonArray arr = new JsonArray();
        for (ChatApi.ChatMessage m : messages) {
            JsonObject one = new JsonObject();
            one.addProperty("role", m.role);
            one.addProperty("content", m.content);
            arr.add(one);
        }
        request.add("messages", arr);
        List<String> stops = parseStopSequences(using.stop);
        if (stops != null && !stops.isEmpty()) {
            JsonArray stopArr = new JsonArray();
            for (String s : stops) stopArr.add(s);
            request.add("stop", stopArr);
        }
        JsonObject reasoning = ProviderRequestOptionsBuilder.buildReasoningConfig(providerId, using);
        if (reasoning != null) request.add("reasoning", reasoning);
        JsonObject providerOptions = ProviderRequestOptionsBuilder.buildProviderOptions(providerId, using);
        if (providerOptions != null) request.add("providerOptions", providerOptions);
        Log.d(TAG, "stream request providerId=" + providerId
                + ", model=" + config.modelId
                + ", thinking=" + using.thinking
                + ", stopCount=" + (stops != null ? stops.size() : 0)
                + ", reasoning=" + (reasoning != null ? reasoning.toString() : "null")
                + ", providerOptions=" + (providerOptions != null ? providerOptions.toString() : "null"));

        Request.Builder rb = new Request.Builder()
                .url(chatUrl)
                .addHeader("Accept", "text/event-stream")
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(request.toString(), okhttp3.MediaType.parse("application/json")));
        if (config.apiKey != null && !config.apiKey.trim().isEmpty()) {
            rb.addHeader("Authorization", "Bearer " + config.apiKey.trim());
        }
        Request okRequest = rb.build();

        long start = System.currentTimeMillis();
        okhttp3.Call call = client.newCall(okRequest);
        handle.bindOkHttpCall(call);
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, java.io.IOException e) {
                if (handle.isCancelled() || (call != null && call.isCanceled())) {
                    fireCancelledOnce(callback, handle);
                    return;
                }
                callback.onError(e != null ? e.getMessage() : "流式请求失败");
            }

            @Override
            public void onResponse(okhttp3.Call call, Response response) {
                if (handle.isCancelled()) {
                    fireCancelledOnce(callback, handle);
                    return;
                }
                if (!response.isSuccessful()) {
                    String detail = "";
                    try (ResponseBody body = response.body()) {
                        if (body != null) detail = body.string();
                    } catch (Exception ignored) {}
                    callback.onError("请求失败: " + response.code()
                            + "\nURL: " + chatUrl
                            + (detail.isEmpty() ? "" : ("\n" + detail)));
                    return;
                }
                StringBuilder fullContent = new StringBuilder();
                StringBuilder fullReasoning = new StringBuilder();
                int promptTokens = 0, completionTokens = 0, totalTokens = 0;
                try (ResponseBody body = response.body()) {
                    if (body == null) {
                        callback.onError("流式响应为空");
                        return;
                    }
                    okio.BufferedSource source = body.source();
                    while (!source.exhausted()) {
                        if (handle.isCancelled()) {
                            fireCancelledOnce(callback, handle);
                            return;
                        }
                        String line = source.readUtf8Line();
                        if (line == null) break;
                        String trimmed = line.trim();
                        String payload;
                        if (trimmed.startsWith("data:")) {
                            payload = trimmed.substring(5).trim();
                        } else if (trimmed.startsWith("{")) {
                            payload = trimmed;
                        } else {
                            continue;
                        }
                        if (payload.isEmpty()) continue;
                        if ("[DONE]".equals(payload)) break;
                        try {
                            JsonObject obj = new JsonParser().parse(payload).getAsJsonObject();
                            JsonObject usage = obj.has("usage") && obj.get("usage").isJsonObject()
                                    ? obj.getAsJsonObject("usage") : null;
                            if (usage != null) {
                                promptTokens = getInt(usage, "prompt_tokens");
                                completionTokens = getInt(usage, "completion_tokens");
                                totalTokens = getInt(usage, "total_tokens");
                            }
                            JsonArray choices = obj.has("choices") && obj.get("choices").isJsonArray()
                                    ? obj.getAsJsonArray("choices") : null;
                            if (choices == null || choices.size() == 0) continue;
                            JsonObject first = choices.get(0).isJsonObject() ? choices.get(0).getAsJsonObject() : null;
                            if (first == null) continue;
                            JsonObject delta = first.has("delta") && first.get("delta").isJsonObject()
                                    ? first.getAsJsonObject("delta")
                                    : first.has("message") && first.get("message").isJsonObject()
                                    ? first.getAsJsonObject("message") : null;
                            if (delta == null) continue;
                            String contentDelta = getString(delta, "content");
                            if (!contentDelta.isEmpty()) {
                                fullContent.append(contentDelta);
                                callback.onPartial(contentDelta);
                            }
                            String reasoningDelta = getString(delta, "reasoning_content");
                            if (reasoningDelta.isEmpty()) reasoningDelta = getString(delta, "reasoning");
                            if (reasoningDelta.isEmpty()) reasoningDelta = getString(delta, "thinking");
                            if (using.thinking && !reasoningDelta.isEmpty()) {
                                fullReasoning.append(reasoningDelta);
                                callback.onReasoning(fullReasoning.toString());
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    if (handle.isCancelled() || (call != null && call.isCanceled())) {
                        fireCancelledOnce(callback, handle);
                        return;
                    }
                    callback.onError("流式解析失败: " + (e != null ? e.getMessage() : ""));
                    return;
                }
                if (handle.isCancelled()) {
                    fireCancelledOnce(callback, handle);
                    return;
                }
                callback.onUsage(promptTokens, completionTokens, totalTokens, System.currentTimeMillis() - start);
                callback.onSuccess(fullContent.toString());
            }
        });
    }

    private void fireCancelledOnce(ChatCallback callback, ChatHandleImpl handle) {
        if (callback == null || handle == null) return;
        if (!handle.tryFireCancelled()) return;
        callback.onCancelled();
    }

    private int getInt(JsonObject obj, String key) {
        try {
            JsonElement e = obj.get(key);
            if (e == null || e.isJsonNull()) return 0;
            return e.getAsInt();
        } catch (Exception ex) {
            return 0;
        }
    }

    private String getString(JsonObject obj, String key) {
        try {
            JsonElement e = obj.get(key);
            if (e == null || e.isJsonNull()) return "";
            return e.getAsString();
        } catch (Exception ex) {
            return "";
        }
    }

    private List<String> parseStopSequences(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            if (line == null) continue;
            String one = line.trim();
            if (!one.isEmpty()) out.add(one);
        }
        return out.isEmpty() ? null : out;
    }

    private String resolveProviderId(String selectedProviderId, String apiHost) {
        String pid = selectedProviderId != null ? selectedProviderId.trim().toLowerCase() : "";
        if (!pid.isEmpty()) return pid;
        String host = apiHost != null ? apiHost.trim().toLowerCase() : "";
        if (host.contains("127.0.0.1:8080") || host.contains("localhost:8080")) return "llama";
        if (host.contains("127.0.0.1:11434") || host.contains("localhost:11434")) return "ollama";
        if (host.contains("127.0.0.1:1234") || host.contains("localhost:1234")) return "lmstudio";
        if (host.contains("openai.com")) return "openai";
        if (host.contains("openrouter.ai")) return "openrouter";
        if (host.contains("googleapis.com") || host.contains("generativelanguage")) return "gemini";
        return "";
    }

    public interface ChatCallback {
        void onSuccess(String content);
        void onError(String message);
        default void onCancelled() {}
        default void onPartial(String delta) {}
        default void onReasoning(String reasoning) {}
        default void onUsage(int promptTokens, int completionTokens, int totalTokens, long elapsedMs) {}
    }
}
