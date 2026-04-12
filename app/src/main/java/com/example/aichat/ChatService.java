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
            callback.onError(context.getString(R.string.error_config_parse_failed, e != null ? e.getMessage() : ""));
            return handle;
        }
        if (config == null || !config.isValid()) {
            callback.onError(context.getString(R.string.error_no_chat_model_selected));
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
            callback.onError(context.getString(R.string.error_api_url_invalid));
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
                                String content = extractAssistantContent(body);
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
                    callback.onError(context.getString(R.string.error_parse_response_failed, e != null ? e.getMessage() : ""));
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
            callback.onError(context.getString(R.string.error_message_empty));
            return;
        }
        AiModelConfig.ResolvedConfig config;
        try {
            config = new AiModelConfig(context).getConfigForThreadNaming();
        } catch (Exception e) {
            callback.onError(context.getString(R.string.error_config_parse_failed, ""));
            return;
        }
        if (config == null || !config.isValid()) {
            callback.onError(context.getString(R.string.error_no_naming_model_selected));
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

        boolean localOpenAiCompat = isLocalOpenAiCompatibleProvider(providerId);
        int timeoutSec = localOpenAiCompat ? 45 : 15;

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .writeTimeout(timeoutSec, TimeUnit.SECONDS)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        ChatApi api = retrofit.create(ChatApi.class);

        List<ChatApi.ChatMessage> requestMessages = new ArrayList<>();
        String titlePrompt = "你是标题助手。根据输入生成一个中文短标题。\n"
                + "仅输出一个JSON对象，不要任何额外文本。\n"
                + "严格格式:{\"title\":\"3到12个字中文短标题\"}\n"
                + "约束: 不要标点，不要换行，不要解释。\n"
                + "输入:" + source;
        requestMessages.add(new ChatApi.ChatMessage("user", titlePrompt));

        ChatApi.ChatRequest request = new ChatApi.ChatRequest();
        request.model = config.modelId;
        request.messages = requestMessages;
        request.stream = false;
        request.n = 1;
        request.maxTokens = 512;
        request.temperature = 0.0;
        request.topP = 0.2;
        request.stop = null;
        request.thinking = localOpenAiCompat ? Boolean.FALSE : null;
        request.reasoning = buildNoThinkingReasoning(providerId, localOpenAiCompat);
        if (!localOpenAiCompat) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            request.responseFormat = responseFormat;
        } else {
            request.responseFormat = null;
        }
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
                    callback.onError(context.getString(R.string.error_naming_failed, String.valueOf(response != null ? response.code() : "无响应"))
                            + (detail.isEmpty() ? "" : ("\n" + detail)));
                    return;
                }
                String raw = extractAssistantContent(response.body());
                String title = extractTitleFromJsonOrText(raw);
                title = cleanTitleResult(title);
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
            callback.onError(context.getString(R.string.error_config_parse_failed, ""));
            return;
        }
        if (config == null || !config.isValid()) {
            callback.onError(context.getString(R.string.error_no_summary_model_selected));
            return;
        }

        String providerId = "";
        String summaryPreset = new ModelConfig(context).getSummaryPreset();
        if (summaryPreset != null && summaryPreset.contains(":")) {
            providerId = summaryPreset.substring(0, summaryPreset.indexOf(':'));
        }
        providerId = resolveProviderId(providerId, config.apiHost);

        String baseUrl = config.toRetrofitBaseUrl();
        if (!baseUrl.endsWith("/")) baseUrl += "/";

        boolean localOpenAiCompat = isLocalOpenAiCompatibleProvider(providerId);
        int timeoutSec = localOpenAiCompat ? 60 : 20;

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .writeTimeout(timeoutSec, TimeUnit.SECONDS)
                .build();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        ChatApi api = retrofit.create(ChatApi.class);

        List<ChatApi.ChatMessage> requestMessages = new ArrayList<>();
        requestMessages.add(new ChatApi.ChatMessage("system",
                "你是对话大纲助手。请根据输入对话生成“信息保真”的大纲正文（80到320字），宁可稍长也不要遗漏关键信息。\n"
                        + "仅输出一个JSON对象，不要任何额外文本。\n"
                        + "严格格式:{\"outline\":\"...\"}\n"
                        + "强约束:\n"
                        + "1) 输出必须以 { 开始、以 } 结束。\n"
                        + "2) 只允许一个键 outline，不要额外键。\n"
                        + "3) 不要Markdown代码块，不要解释，不要Thinking/Reasoning文本。\n"
                        + "4) outline 内容不要标题，不要列表。\n"
                        + "5) 必须保留关键细节：人物/对象名称、核心事件、动机或目标、约束条件、结果或当前进展。\n"
                        + "6) 若原文出现时间、地点、数字、专有名词、规则设定，优先保留，不要泛化改写。\n"
                        + "7) 避免空泛词（如“发生了一些事”“进行了讨论”），改为具体事实。"));
        requestMessages.add(new ChatApi.ChatMessage("user", prompt));

        ChatApi.ChatRequest request = new ChatApi.ChatRequest();
        request.model = config.modelId;
        request.messages = requestMessages;
        request.stream = false;
        request.n = 1;
        request.maxTokens = 620;
        request.temperature = 0.2;
        request.topP = 0.8;
        request.stop = null;
        request.thinking = localOpenAiCompat ? Boolean.FALSE : null;
        request.reasoning = buildNoThinkingReasoning(providerId, localOpenAiCompat);
        if (!localOpenAiCompat) {
            JsonObject outlineResponseFormat = new JsonObject();
            outlineResponseFormat.addProperty("type", "json_object");
            request.responseFormat = outlineResponseFormat;
        } else {
            request.responseFormat = null;
        }
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
                        String outline = extractAssistantContent(response.body());
                        outline = extractTextFieldFromJsonOrText(outline, "outline", "summary", "content", "result");
                        outline = stripThinkTags(outline).replace("\n", " ").trim();
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

    public void summarizeMessageForOutline(String content, ChatCallback callback) {
        if (callback == null) return;
        String source = content != null ? content.trim() : "";
        if (source.isEmpty()) {
            callback.onError(context.getString(R.string.error_message_empty));
            return;
        }
        if (source.length() > 2500) {
            source = source.substring(0, 2500);
        }
        AiModelConfig.ResolvedConfig config;
        try {
            config = new AiModelConfig(context).getConfigForSummary();
        } catch (Exception e) {
            callback.onError(context.getString(R.string.error_config_parse_failed, ""));
            return;
        }
        if (config == null || !config.isValid()) {
            callback.onError(context.getString(R.string.error_no_summary_model_selected));
            return;
        }

        String providerId = "";
        String summaryPreset = new ModelConfig(context).getSummaryPreset();
        if (summaryPreset != null && summaryPreset.contains(":")) {
            providerId = summaryPreset.substring(0, summaryPreset.indexOf(':'));
        }
        providerId = resolveProviderId(providerId, config.apiHost);

        String baseUrl = config.toRetrofitBaseUrl();
        if (!baseUrl.endsWith("/")) baseUrl += "/";

        boolean localOpenAiCompat = isLocalOpenAiCompatibleProvider(providerId);
        int timeoutSec = localOpenAiCompat ? 60 : 20;

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .writeTimeout(timeoutSec, TimeUnit.SECONDS)
                .build();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        ChatApi api = retrofit.create(ChatApi.class);

        List<ChatApi.ChatMessage> requestMessages = new ArrayList<>();
        requestMessages.add(new ChatApi.ChatMessage("system",
                "你是小说写作助手。请把输入内容提炼为可放入大纲的条目正文（80到280字），要求细节充分、便于后续续写。\n"
                        + "仅输出一个JSON对象，不要任何额外文本。\n"
                        + "严格格式:{\"summary\":\"...\"}\n"
                        + "强约束:\n"
                        + "1) 输出必须以 { 开始、以 } 结束。\n"
                        + "2) 只允许一个键 summary，不要额外键。\n"
                        + "3) 不要Markdown代码块，不要解释，不要Thinking/Reasoning文本。\n"
                        + "4) summary 内容不要标题，不要列表。\n"
                        + "5) 必须覆盖：关键事件经过、人物意图/冲突、重要设定或规则、任务线索与阶段结果。\n"
                        + "6) 保留可复用细节：时间地点、名称称谓、数字阈值、道具/能力/组织名等。\n"
                        + "7) 不要只写结论，需包含必要过程与因果关系。"));
        requestMessages.add(new ChatApi.ChatMessage("user", source));

        ChatApi.ChatRequest request = new ChatApi.ChatRequest();
        request.model = config.modelId;
        request.messages = requestMessages;
        request.stream = false;
        request.n = 1;
        request.maxTokens = 520;
        request.temperature = 0.2;
        request.topP = 0.8;
        request.stop = null;
        request.thinking = localOpenAiCompat ? Boolean.FALSE : null;
        request.reasoning = buildNoThinkingReasoning(providerId, localOpenAiCompat);
        if (!localOpenAiCompat) {
            JsonObject summaryResponseFormat = new JsonObject();
            summaryResponseFormat.addProperty("type", "json_object");
            request.responseFormat = summaryResponseFormat;
        } else {
            request.responseFormat = null;
        }
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
                            callback.onError("总结失败: " + (response != null ? response.code() : "无响应")
                                    + (detail.isEmpty() ? "" : ("\n" + detail)));
                            return;
                        }
                        String summary = extractAssistantContent(response.body());
                        summary = extractTextFieldFromJsonOrText(summary, "summary", "outline", "content", "result");
                        summary = stripThinkTags(summary).replace("\n", " ").trim();
                        if (summary.isEmpty()) {
                            callback.onError("总结失败");
                            return;
                        }
                        callback.onSuccess(summary);
                    }

                    @Override
                    public void onFailure(retrofit2.Call<ChatApi.ChatResponse> call, Throwable t) {
                        callback.onError(t != null ? t.getMessage() : "总结失败");
                    }
                });
    }

    public void generateChapterPlanJson(String userInput, String storyContext, ChatCallback callback) {
        if (callback == null) return;
        String input = userInput != null ? userInput.trim() : "";
        String contextText = storyContext != null ? storyContext.trim() : "";
        if (input.isEmpty()) {
            callback.onError("输入为空，无法生成章节计划");
            return;
        }
        if (contextText.length() > 2800) {
            contextText = contextText.substring(0, 2800);
        }

        AiModelConfig.ResolvedConfig config;
        try {
            config = new AiModelConfig(context).getConfigForNovelSharp();
        } catch (Exception e) {
            callback.onError("配置解析失败");
            return;
        }
        if (config == null || !config.isValid()) {
            callback.onError(context.getString(R.string.error_no_novel_model_selected));
            return;
        }

        String providerId = "";
        String preset = new ModelConfig(context).getNovelSharpPreset();
        if (preset != null && preset.contains(":")) {
            providerId = preset.substring(0, preset.indexOf(':'));
        }
        providerId = resolveProviderId(providerId, config.apiHost);

        String baseUrl = config.toRetrofitBaseUrl();
        if (!baseUrl.endsWith("/")) baseUrl += "/";

        boolean localOpenAiCompat = isLocalOpenAiCompatibleProvider(providerId);
        int timeoutSec = localOpenAiCompat ? 60 : 45;

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .writeTimeout(timeoutSec, TimeUnit.SECONDS)
                .build();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        ChatApi api = retrofit.create(ChatApi.class);

        List<ChatApi.ChatMessage> requestMessages = new ArrayList<>();
        requestMessages.add(new ChatApi.ChatMessage("system",
                "你是小说章节规划助手。请为“本轮写作”生成可执行计划，并严格输出 JSON 对象。\n"
                        + "仅输出一个JSON对象，不要任何额外文本。\n"
                        + "严格键集合:\n"
                        + "{\"chapterGoal\":\"\",\"startState\":\"\",\"endState\":\"\",\"characterDrives\":[],\"knowledgeBoundary\":[],\"eventChain\":[],\"foreshadow\":[],\"payoff\":[],\"forbidden\":[],\"styleGuide\":\"\"}\n"
                        + "约束:\n"
                        + "1) 输出必须以 { 开始、以 } 结束。\n"
                        + "2) 必须保留全部键，禁止新增或删除键。\n"
                        + "3) 除 characterDrives 外，数组元素都用字符串；characterDrives 用对象数组，结构为 {\"name\":\"\",\"goal\":\"\",\"misbelief\":\"\",\"emotion\":\"\"}。\n"
                        + "4) 内容具体可执行，避免空话。"));
        requestMessages.add(new ChatApi.ChatMessage("user",
                "【用户本轮输入】\n" + input
                        + (contextText.isEmpty() ? "" : ("\n\n【当前写作上下文】\n" + contextText))));

        ChatApi.ChatRequest request = new ChatApi.ChatRequest();
        request.model = config.modelId;
        request.messages = requestMessages;
        request.stream = false;
        request.n = 1;
        request.maxTokens = 800;
        request.temperature = 0.15;
        request.topP = 0.6;
        request.stop = null;
        // Keep chapter-plan request minimal for broad compatibility and lower latency.
        request.thinking = null;
        request.reasoning = null;
        request.responseFormat = null;
        request.providerOptions = null;

        String auth = (config.apiKey != null && !config.apiKey.trim().isEmpty())
                ? ("Bearer " + config.apiKey.trim()) : null;
        String chatUrl = ApiUtils.toBaseUrl(config.apiHost, config.apiPath);
        Log.d(TAG, "generateChapterPlanJson providerId=" + providerId
                + ", model=" + config.modelId
                + ", localOpenAiCompat=" + localOpenAiCompat
                + ", thinking=" + request.thinking
                + ", reasoning=" + (request.reasoning != null ? request.reasoning.toString() : "null")
                + ", responseFormat=" + (request.responseFormat != null ? request.responseFormat.toString() : "null"));
        callback.onPartial("正在请求章节计划模型…");
        requestChapterPlanWithFallback(api, chatUrl, auth, request, callback, true);
    }

    private void requestChapterPlanWithFallback(ChatApi api,
                                                String chatUrl,
                                                String auth,
                                                ChatApi.ChatRequest request,
                                                ChatCallback callback,
                                                boolean allowFallback) {
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
                            if (allowFallback && shouldRetryWithoutAdvancedParams(detail)) {
                                callback.onPartial("参数兼容中，正在重试…");
                                Log.w(TAG, "chapter plan retry without advanced params, detail=" + detail);
                                requestChapterPlanWithFallback(
                                        api,
                                        chatUrl,
                                        auth,
                                        buildChapterPlanFallbackRequest(request),
                                        callback,
                                        false);
                                return;
                            }
                            callback.onError("章节计划生成失败: " + (response != null ? response.code() : "无响应")
                                    + (detail.isEmpty() ? "" : ("\n" + detail)));
                            return;
                        }
                        callback.onPartial("模型已返回，正在解析计划…");
                        String raw = extractAssistantContent(response.body());
                        Log.d(TAG, "chapter plan raw length=" + (raw != null ? raw.length() : 0)
                                + ", preview=" + previewForLog(raw, 180));
                        JsonObject obj = parseFirstJsonObject(raw);
                        if (obj == null) {
                            String preview = raw != null ? raw.trim() : "";
                            String head = preview;
                            String tail = "";
                            if (head.length() > 120) {
                                head = head.substring(0, 120) + "...";
                                int start = Math.max(0, preview.length() - 120);
                                tail = "...\n末尾片段: " + preview.substring(start);
                            }
                            callback.onError("章节计划解析失败"
                                    + (preview.isEmpty() ? "" : ("\n返回长度: " + preview.length()
                                    + "\n开头片段: " + head + tail)));
                            return;
                        }
                        callback.onPartial("章节计划已生成");
                        JsonObject normalized = normalizeChapterPlanJson(obj);
                        Log.d(TAG, "chapter plan normalized nonEmptyFields=" + countNonEmptyPlanFields(normalized)
                                + ", payload=" + previewForLog(normalized.toString(), 220));
                        callback.onSuccess(normalized.toString());
                    }

                    @Override
                    public void onFailure(retrofit2.Call<ChatApi.ChatResponse> call, Throwable t) {
                        String reason = t != null ? t.getMessage() : "章节计划生成失败";
                        callback.onError("章节计划生成失败(" + request.model + "): " + reason);
                    }
                });
    }

    private ChatApi.ChatRequest buildChapterPlanFallbackRequest(ChatApi.ChatRequest source) {
        ChatApi.ChatRequest request = new ChatApi.ChatRequest();
        request.model = source != null ? source.model : null;
        request.messages = source != null ? source.messages : null;
        request.stream = false;
        request.n = null;
        request.maxTokens = source != null ? source.maxTokens : null;
        request.temperature = null;
        request.topP = null;
        request.stop = null;
        request.thinking = null;
        request.reasoning = null;
        request.responseFormat = null;
        request.providerOptions = null;
        return request;
    }

    private boolean shouldRetryWithoutAdvancedParams(String detail) {
        if (detail == null || detail.trim().isEmpty()) return false;
        String lower = detail.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("invalid_request_error")) return true;
        if (lower.contains("unknown parameter")) return true;
        if (lower.contains("invalid parameter")) return true;
        if (lower.contains("unsupported parameter")) return true;
        if (lower.contains("response_format")) return true;
        if (lower.contains("reasoning")) return true;
        if (lower.contains("thinking")) return true;
        if (lower.contains("temperature")) return true;
        return lower.contains("top_p");
    }

    private JsonObject parseFirstJsonObject(String raw) {
        String text = sanitizeJsonLikeText(stripThinkTags(raw));
        if (text.isEmpty()) return null;
        // 1) Full parse first: parse the whole payload as a JSON object.
        JsonObject direct = tryParseObject(text);
        if (direct != null) return direct;

        // 2) Full-slice parse: from first '{' to last '}' as one complete object.
        String fullSlice = extractJsonObjectSlice(text);
        JsonObject fullObj = tryParseObject(fullSlice);
        if (fullObj != null) return fullObj;

        // 3) Only if likely truncated/non-normal ending, run fallback extraction.
        if (looksLikeTruncatedJson(text)) {
            String repaired = repairTruncatedJsonObject(text);
            JsonObject repairedObj = tryParseObject(repaired);
            if (repairedObj != null) return repairedObj;
            JsonObject keywordObj = extractChapterPlanByKeywords(text);
            if (keywordObj != null) return keywordObj;
        }
        return null;
    }

    private JsonObject tryParseObject(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            return new JsonParser().parse(text).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean looksLikeTruncatedJson(String text) {
        if (text == null || text.isEmpty()) return false;
        int first = text.indexOf('{');
        if (first < 0) return false;
        int objDepth = 0;
        int arrDepth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = first; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') objDepth++;
            else if (c == '}') objDepth = Math.max(0, objDepth - 1);
            else if (c == '[') arrDepth++;
            else if (c == ']') arrDepth = Math.max(0, arrDepth - 1);
        }
        return inString || objDepth > 0 || arrDepth > 0;
    }

    private JsonObject extractChapterPlanByKeywords(String text) {
        if (text == null || text.isEmpty()) return null;
        JsonObject out = new JsonObject();

        putIfNotEmpty(out, "chapterGoal", extractStringByKeys(text,
                "chapterGoal", "chapter_goal", "goal", "章节目标", "本章目标", "目标"));
        putIfNotEmpty(out, "startState", extractStringByKeys(text,
                "startState", "start_state", "起始状态", "开场状态", "开局状态"));
        putIfNotEmpty(out, "endState", extractStringByKeys(text,
                "endState", "end_state", "结束状态", "结尾状态", "收束状态"));
        putIfNotEmpty(out, "styleGuide", extractStringByKeys(text,
                "styleGuide", "style_guide", "style", "writingStyle", "文风", "文风与节奏"));

        putArrayIfNotEmpty(out, "knowledgeBoundary", extractArrayByKeys(text,
                "knowledgeBoundary", "knowledge_boundary", "knowledge", "知情边界", "知情约束"));
        putArrayIfNotEmpty(out, "eventChain", extractArrayByKeys(text,
                "eventChain", "event_chain", "events", "事件链", "关键事件"));
        putArrayIfNotEmpty(out, "foreshadow", extractArrayByKeys(text,
                "foreshadow", "foreshadows", "伏笔"));
        putArrayIfNotEmpty(out, "payoff", extractArrayByKeys(text,
                "payoff", "payoffs", "回收"));
        putArrayIfNotEmpty(out, "forbidden", extractArrayByKeys(text,
                "forbidden", "forbiddenList", "禁写清单", "禁写", "禁忌"));
        putCharacterDrivesIfNotEmpty(out, extractArrayByKeys(text,
                "characterDrives", "character_drives", "characters", "角色驱动", "角色动机"));

        return out.entrySet().isEmpty() ? null : out;
    }

    private void putIfNotEmpty(JsonObject obj, String key, String value) {
        if (obj == null || key == null) return;
        if (value == null || value.trim().isEmpty()) return;
        obj.addProperty(key, value.trim());
    }

    private void putArrayIfNotEmpty(JsonObject obj, String key, java.util.List<String> values) {
        if (obj == null || key == null || values == null || values.isEmpty()) return;
        JsonArray arr = new JsonArray();
        for (String v : values) {
            if (v == null || v.trim().isEmpty()) continue;
            arr.add(v.trim());
        }
        if (arr.size() > 0) obj.add(key, arr);
    }

    private void putCharacterDrivesIfNotEmpty(JsonObject obj, java.util.List<String> drives) {
        if (obj == null || drives == null || drives.isEmpty()) return;
        JsonArray arr = new JsonArray();
        for (String v : drives) {
            if (v == null || v.trim().isEmpty()) continue;
            JsonObject one = new JsonObject();
            one.addProperty("name", "");
            one.addProperty("goal", v.trim());
            one.addProperty("misbelief", "");
            one.addProperty("emotion", "");
            arr.add(one);
        }
        if (arr.size() > 0) obj.add("characterDrives", arr);
    }

    private String extractStringByKeys(String text, String... keys) {
        if (text == null || keys == null) return "";
        for (String key : keys) {
            if (key == null || key.isEmpty()) continue;
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "\""+ java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(text);
            if (m.find()) {
                String v = m.group(1);
                if (v != null && !v.trim().isEmpty()) return v.trim();
            }
        }
        return "";
    }

    private java.util.List<String> extractArrayByKeys(String text, String... keys) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null || keys == null) return out;
        for (String key : keys) {
            if (key == null || key.isEmpty()) continue;
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "\""+ java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\\[(.*?)\\]",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(text);
            if (!m.find()) continue;
            String body = m.group(1);
            if (body == null || body.trim().isEmpty()) continue;
            java.util.regex.Matcher item = java.util.regex.Pattern
                    .compile("\"([^\"]*)\"")
                    .matcher(body);
            while (item.find()) {
                String v = item.group(1);
                if (v != null && !v.trim().isEmpty()) out.add(v.trim());
            }
            if (!out.isEmpty()) return out;
        }
        return out;
    }

    private String sanitizeJsonLikeText(String text) {
        String out = text != null ? text.trim() : "";
        if (out.isEmpty()) return "";
        // Remove fenced code markers.
        out = out.replaceAll("(?is)^```(?:json)?\\s*", "");
        out = out.replaceAll("(?is)\\s*```$", "");
        // Normalize full-width punctuation often seen in CJK outputs.
        out = out.replace('“', '"').replace('”', '"')
                .replace('‘', '\'').replace('’', '\'')
                .replace('：', ':')
                .replace('，', ',');
        return out.trim();
    }

    private String repairJsonCandidate(String candidate) {
        String out = sanitizeJsonLikeText(candidate);
        if (out.isEmpty()) return "";
        // Try converting single-quoted JSON-like text to valid double-quoted JSON.
        out = out.replaceAll("(?<!\\\\)'", "\"");
        // Remove trailing commas before closing braces/brackets.
        out = out.replaceAll(",\\s*([}\\]])", "$1");
        return out;
    }

    private String repairTruncatedJsonObject(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        int start = raw.indexOf('{');
        if (start < 0) return "";
        String text = raw.substring(start);
        StringBuilder out = new StringBuilder(text);
        java.util.ArrayDeque<Character> closers = new java.util.ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') closers.push('}');
            else if (c == '[') closers.push(']');
            else if (c == '}' || c == ']') {
                if (!closers.isEmpty() && closers.peek() == c) closers.pop();
                else return "";
            }
        }
        if (inString) return "";
        while (!closers.isEmpty()) out.append(closers.pop());
        String fixed = out.toString().replaceAll(",\\s*([}\\]])", "$1");
        return fixed;
    }

    public void auditNovelLeakage(String knowledgeConstraints, String assistantContent, ChatCallback callback) {
        if (callback == null) return;
        String constraints = knowledgeConstraints != null ? knowledgeConstraints.trim() : "";
        String aiText = assistantContent != null ? assistantContent.trim() : "";
        if (constraints.isEmpty()) {
            callback.onError("知情约束为空");
            return;
        }
        if (aiText.isEmpty()) {
            callback.onError("待审计内容为空");
            return;
        }
        if (aiText.length() > 4000) {
            aiText = aiText.substring(0, 4000);
        }
        AiModelConfig.ResolvedConfig config;
        try {
            config = new AiModelConfig(context).getConfigForSummary();
        } catch (Exception e) {
            callback.onError(context.getString(R.string.error_config_parse_failed, ""));
            return;
        }
        if (config == null || !config.isValid()) {
            callback.onError(context.getString(R.string.error_no_summary_model_selected));
            return;
        }

        String providerId = "";
        String summaryPreset = new ModelConfig(context).getSummaryPreset();
        if (summaryPreset != null && summaryPreset.contains(":")) {
            providerId = summaryPreset.substring(0, summaryPreset.indexOf(':'));
        }
        providerId = resolveProviderId(providerId, config.apiHost);

        String baseUrl = config.toRetrofitBaseUrl();
        if (!baseUrl.endsWith("/")) baseUrl += "/";

        boolean localOpenAiCompat = isLocalOpenAiCompatibleProvider(providerId);
        int timeoutSec = localOpenAiCompat ? 60 : 20;

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .writeTimeout(timeoutSec, TimeUnit.SECONDS)
                .build();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        ChatApi api = retrofit.create(ChatApi.class);

        List<ChatApi.ChatMessage> requestMessages = new ArrayList<>();
        requestMessages.add(new ChatApi.ChatMessage("system",
                "你是小说写作审计员。请依据“知情约束”检查文本是否存在角色越权知情（泄密）问题。\n"
                        + "仅输出一个JSON对象，不要任何额外文本。\n"
                        + "严格格式:{\"report\":\"结论：通过/不通过\\n风险点：...\\n修复建议：...\"}\n"
                        + "强约束:\n"
                        + "1) 输出必须以 { 开始、以 } 结束。\n"
                        + "2) 只允许一个键 report，不要额外键。\n"
                        + "3) 不要Markdown代码块，不要解释，不要Thinking/Reasoning文本。\n"
                        + "4) 风险点逐条列出（若无写“无”），修复建议需可执行（若通过可写“保持当前写法”）。"));
        requestMessages.add(new ChatApi.ChatMessage("user",
                "【知情约束】\n" + constraints + "\n\n【待审计文本】\n" + aiText));

        ChatApi.ChatRequest request = new ChatApi.ChatRequest();
        request.model = config.modelId;
        request.messages = requestMessages;
        request.stream = false;
        request.n = 1;
        request.maxTokens = 520;
        request.temperature = 0.1;
        request.topP = 0.8;
        request.stop = null;
        request.thinking = localOpenAiCompat ? Boolean.FALSE : null;
        request.reasoning = buildNoThinkingReasoning(providerId, localOpenAiCompat);
        if (!localOpenAiCompat) {
            JsonObject auditResponseFormat = new JsonObject();
            auditResponseFormat.addProperty("type", "json_object");
            request.responseFormat = auditResponseFormat;
        } else {
            request.responseFormat = null;
        }
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
                            callback.onError("审计失败: " + (response != null ? response.code() : "无响应")
                                    + (detail.isEmpty() ? "" : ("\n" + detail)));
                            return;
                        }
                        String report = extractAssistantContent(response.body());
                        report = extractTextFieldFromJsonOrText(report, "report", "summary", "content", "result");
                        report = stripThinkTags(report).trim();
                        if (report.isEmpty()) {
                            callback.onError("审计失败");
                            return;
                        }
                        callback.onSuccess(report);
                    }

                    @Override
                    public void onFailure(retrofit2.Call<ChatApi.ChatResponse> call, Throwable t) {
                        callback.onError(t != null ? t.getMessage() : "审计失败");
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
        boolean shouldShowReasoning = shouldShowReasoning(using, providerId, config != null ? config.modelId : null);
        Log.d(TAG, "stream request providerId=" + providerId
                + ", model=" + config.modelId
                + ", thinking=" + using.thinking
                + ", showReasoning=" + shouldShowReasoning
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
                InlineThinkState inlineThinkState = new InlineThinkState();
                boolean normalizeInlineThink = shouldNormalizeInlineThink(providerId);
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
                            boolean emittedInlineReasoning = false;
                            if (!contentDelta.isEmpty()) {
                                if (normalizeInlineThink) {
                                    ContentReasoningParts parts = splitInlineThink(contentDelta, inlineThinkState, false);
                                    if (!parts.content.isEmpty()) {
                                        fullContent.append(parts.content);
                                        callback.onPartial(parts.content);
                                    }
                                    if (!parts.reasoning.isEmpty()) {
                                        emittedInlineReasoning = true;
                                        if (shouldShowReasoning) {
                                            fullReasoning.append(parts.reasoning);
                                            callback.onReasoning(fullReasoning.toString());
                                        }
                                    }
                                } else {
                                    fullContent.append(contentDelta);
                                    callback.onPartial(contentDelta);
                                }
                            }
                            String reasoningDelta = extractReasoningDelta(obj, first, delta);
                            if (shouldShowReasoning && !reasoningDelta.isEmpty() && !emittedInlineReasoning) {
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
                if (normalizeInlineThink) {
                    ContentReasoningParts tail = splitInlineThink("", inlineThinkState, true);
                    if (!tail.content.isEmpty()) {
                        fullContent.append(tail.content);
                        callback.onPartial(tail.content);
                    }
                    if (shouldShowReasoning && !tail.reasoning.isEmpty()) {
                        fullReasoning.append(tail.reasoning);
                        callback.onReasoning(fullReasoning.toString());
                    }
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

    private boolean shouldShowReasoning(SessionChatOptions options, String providerId, String modelId) {
        if (isIntrinsicReasoningModel(providerId, modelId)) return true;
        return options != null && options.thinking;
    }

    private boolean isIntrinsicReasoningModel(String providerId, String modelId) {
        String pid = providerId != null ? providerId.trim().toLowerCase() : "";
        String mid = modelId != null ? modelId.trim().toLowerCase() : "";
        if (mid.isEmpty()) return false;
        // Model-driven reasoning families: show reasoning if returned, even when toggle is off.
        if (mid.contains("reasoner")) return true;
        if (mid.contains("deepseek-r1")) return true;
        if (mid.matches(".*(^|[-_/])r1([-. _/]|$).*")) return true;
        // Keep provider hint as fallback for renamed reasoner deployments.
        return "deepseek".equals(pid) && mid.contains("r1");
    }

    private String extractReasoningDelta(JsonObject root, JsonObject choice, JsonObject delta) {
        String v = firstNonEmpty(
                getStringFlexible(delta, "reasoning_content"),
                getStringFlexible(delta, "reasoning"),
                getStringFlexible(delta, "thinking"));
        if (!v.isEmpty()) return v;

        JsonObject messageObj = choice != null && choice.has("message") && choice.get("message").isJsonObject()
                ? choice.getAsJsonObject("message") : null;
        v = firstNonEmpty(
                getStringFlexible(choice, "reasoning_content"),
                getStringFlexible(choice, "reasoning"),
                getStringFlexible(choice, "thinking"),
                getStringFlexible(messageObj, "reasoning_content"),
                getStringFlexible(messageObj, "reasoning"),
                getStringFlexible(messageObj, "thinking"),
                getStringFlexible(root, "reasoning_content"),
                getStringFlexible(root, "reasoning"),
                getStringFlexible(root, "thinking"));
        return v;
    }

    private String getStringFlexible(JsonObject obj, String key) {
        try {
            if (obj == null || key == null || key.isEmpty()) return "";
            JsonElement e = obj.get(key);
            if (e == null || e.isJsonNull()) return "";
            if (e.isJsonPrimitive()) return e.getAsString();
            // Some gateways return reasoning as array/object chunks; keep textual representation.
            return e.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private String firstNonEmpty(String... values) {
        if (values == null || values.length == 0) return "";
        for (String one : values) {
            if (one != null && !one.isEmpty()) return one;
        }
        return "";
    }

    private boolean shouldNormalizeInlineThink(String providerId) {
        String pid = providerId != null ? providerId.trim().toLowerCase() : "";
        return "lmstudio".equals(pid) || "ollama".equals(pid) || isLlamaProviderId(pid);
    }

    private boolean isLocalOpenAiCompatibleProvider(String providerId) {
        String pid = providerId != null ? providerId.trim().toLowerCase() : "";
        if ("lmstudio".equals(pid)) return true;
        if ("ollama".equals(pid)) return true;
        return isLlamaProviderId(pid);
    }

    private boolean isLlamaProviderId(String pid) {
        if (pid == null || pid.isEmpty()) return false;
        return "llama".equals(pid)
                || "llamacpp".equals(pid)
                || "llama.cpp".equals(pid)
                || "llama-cpp".equals(pid);
    }

    private String extractAssistantContent(ChatApi.ChatResponse body) {
        if (body == null || body.choices == null || body.choices.isEmpty()) return "";
        ChatApi.Choice first = body.choices.get(0);
        if (first == null || first.message == null) return "";
        JsonElement content = first.message.content;
        if (content == null || content.isJsonNull()) return "";
        try {
            if (content.isJsonPrimitive()) return content.getAsString();
            if (content.isJsonArray()) {
                StringBuilder out = new StringBuilder();
                JsonArray arr = content.getAsJsonArray();
                for (JsonElement one : arr) {
                    if (one == null || one.isJsonNull()) continue;
                    if (one.isJsonPrimitive()) {
                        out.append(one.getAsString());
                        continue;
                    }
                    if (!one.isJsonObject()) continue;
                    JsonObject obj = one.getAsJsonObject();
                    String txt = firstNonEmpty(
                            getStringFlexible(obj, "text"),
                            getStringFlexible(obj, "content"),
                            getStringFlexible(obj, "value"));
                    if (!txt.isEmpty()) out.append(txt);
                }
                return out.toString();
            }
            if (content.isJsonObject()) {
                JsonObject obj = content.getAsJsonObject();
                return firstNonEmpty(
                        getStringFlexible(obj, "text"),
                        getStringFlexible(obj, "content"),
                        getStringFlexible(obj, "value"));
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String stripThinkTags(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replaceAll("(?is)<think>.*?</think>", "").trim();
    }

    private String cleanTitleResult(String raw) {
        String text = stripThinkTags(raw);
        if (text == null) text = "";
        text = text.replace("\r", "\n").trim();

        // Remove common verbose reasoning prefixes from uncensored/local models.
        text = text.replaceAll("(?is)^\\s*(thinking\\s*process|reasoning|analysis|思考过程|分析过程)\\s*[:：].*$", "");
        if (text.isEmpty()) return "";

        // Prefer first non-empty line that looks like a short Chinese title.
        String[] lines = text.split("\\n+");
        String best = "";
        for (String line : lines) {
            if (line == null) continue;
            String one = line.trim();
            if (one.isEmpty()) continue;
            one = one.replaceAll("^[\\-\\*\\d\\.\\)\\(\\[\\]【】\\s]+", "").trim();
            one = one.replaceAll("[。！？，,.!?:：;；\"'“”‘’（）()\\[\\]{}]", "").trim();
            if (one.isEmpty()) continue;
            if (one.matches(".*[\\u4e00-\\u9fa5].*") && one.length() >= 3 && one.length() <= 12) {
                return one;
            }
            if (best.isEmpty()) best = one;
        }

        if (!best.isEmpty()) {
            best = best.replaceAll("[。！？，,.!?:：;；\"'“”‘’（）()\\[\\]{}]", "").trim();
            return best;
        }
        return text.replace("\n", " ").replaceAll("[。！？，,.!?:：;；\"'“”‘’（）()\\[\\]{}]", "").trim();
    }

    private String extractTitleFromJsonOrText(String raw) {
        String text = raw != null ? raw.trim() : "";
        if (text.isEmpty()) return "";
        try {
            String jsonSlice = extractJsonObjectSlice(text);
            if (!jsonSlice.isEmpty()) {
                JsonObject obj = new JsonParser().parse(jsonSlice).getAsJsonObject();
                String title = firstNonEmpty(
                        getStringFlexible(obj, "title"),
                        getStringFlexible(obj, "name"),
                        getStringFlexible(obj, "result"));
                if (title != null && !title.trim().isEmpty()) return title.trim();
            }
        } catch (Exception ignored) {}
        return text;
    }

    private String extractTextFieldFromJsonOrText(String raw, String... preferredKeys) {
        String text = raw != null ? raw.trim() : "";
        if (text.isEmpty()) return "";
        try {
            String jsonSlice = extractJsonObjectSlice(text);
            if (!jsonSlice.isEmpty()) {
                JsonObject obj = new JsonParser().parse(jsonSlice).getAsJsonObject();
                if (preferredKeys != null) {
                    for (String key : preferredKeys) {
                        String value = getStringFlexible(obj, key);
                        if (value != null && !value.trim().isEmpty()) return value.trim();
                    }
                }
                String fallback = firstNonEmpty(
                        getStringFlexible(obj, "text"),
                        getStringFlexible(obj, "message"),
                        getStringFlexible(obj, "data"));
                if (fallback != null && !fallback.trim().isEmpty()) return fallback.trim();
            }
        } catch (Exception ignored) {}
        return text;
    }

    private JsonObject normalizeChapterPlanJson(JsonObject source) {
        JsonObject out = new JsonObject();
        out.addProperty("chapterGoal", pickString(source, "chapterGoal", "chapter_goal", "goal", "章节目标", "本章目标", "目标"));
        out.addProperty("startState", pickString(source, "startState", "start_state", "起始状态", "开场状态", "开局状态"));
        out.addProperty("endState", pickString(source, "endState", "end_state", "结束状态", "结尾状态", "收束状态"));
        out.add("characterDrives", normalizeCharacterDrives(pickElement(source,
                "characterDrives", "character_drives", "characters", "角色驱动", "角色动机")));
        out.add("knowledgeBoundary", normalizeStringArray(pickElement(source,
                "knowledgeBoundary", "knowledge_boundary", "knowledge", "知情边界", "知情约束")));
        out.add("eventChain", normalizeStringArray(pickElement(source,
                "eventChain", "event_chain", "events", "事件链", "关键事件")));
        out.add("foreshadow", normalizeStringArray(pickElement(source,
                "foreshadow", "foreshadows", "伏笔")));
        out.add("payoff", normalizeStringArray(pickElement(source,
                "payoff", "payoffs", "回收")));
        out.add("forbidden", normalizeStringArray(pickElement(source,
                "forbidden", "forbiddenList", "禁写清单", "禁写", "禁忌")));
        out.addProperty("styleGuide", pickString(source, "styleGuide", "style_guide", "style", "writingStyle", "文风", "文风与节奏"));
        // Keep target length blank so user can decide it manually in dialog.
        out.addProperty("targetLength", "");
        return out;
    }

    private JsonElement pickElement(JsonObject source, String... keys) {
        if (source == null || keys == null) return null;
        for (String key : keys) {
            if (key == null || key.isEmpty()) continue;
            JsonElement e = source.get(key);
            if (e != null && !e.isJsonNull()) return e;
        }
        return null;
    }

    private String pickString(JsonObject source, String... keys) {
        if (source == null || keys == null) return "";
        for (String key : keys) {
            String v = getStringFlexible(source, key);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private int countNonEmptyPlanFields(JsonObject plan) {
        if (plan == null) return 0;
        int count = 0;
        if (!getStringFlexible(plan, "chapterGoal").trim().isEmpty()) count++;
        if (!getStringFlexible(plan, "startState").trim().isEmpty()) count++;
        if (!getStringFlexible(plan, "endState").trim().isEmpty()) count++;
        if (!getStringFlexible(plan, "styleGuide").trim().isEmpty()) count++;
        if (plan.has("characterDrives") && plan.get("characterDrives").isJsonArray()
                && plan.getAsJsonArray("characterDrives").size() > 0) count++;
        if (plan.has("knowledgeBoundary") && plan.get("knowledgeBoundary").isJsonArray()
                && plan.getAsJsonArray("knowledgeBoundary").size() > 0) count++;
        if (plan.has("eventChain") && plan.get("eventChain").isJsonArray()
                && plan.getAsJsonArray("eventChain").size() > 0) count++;
        if (plan.has("foreshadow") && plan.get("foreshadow").isJsonArray()
                && plan.getAsJsonArray("foreshadow").size() > 0) count++;
        if (plan.has("payoff") && plan.get("payoff").isJsonArray()
                && plan.getAsJsonArray("payoff").size() > 0) count++;
        if (plan.has("forbidden") && plan.get("forbidden").isJsonArray()
                && plan.getAsJsonArray("forbidden").size() > 0) count++;
        return count;
    }

    private String previewForLog(String text, int maxLen) {
        String v = text != null ? text.replace("\n", "\\n").trim() : "";
        if (v.length() <= Math.max(32, maxLen)) return v;
        return v.substring(0, Math.max(32, maxLen)) + "...";
    }

    private JsonArray normalizeStringArray(JsonElement element) {
        JsonArray out = new JsonArray();
        if (element == null || element.isJsonNull() || !element.isJsonArray()) return out;
        JsonArray arr = element.getAsJsonArray();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement one = arr.get(i);
            if (one == null || one.isJsonNull()) continue;
            if (one.isJsonPrimitive()) out.add(one.getAsString());
            else if (one.isJsonObject()) {
                String text = firstNonEmpty(
                        getStringFlexible(one.getAsJsonObject(), "text"),
                        getStringFlexible(one.getAsJsonObject(), "value"),
                        one.toString());
                if (text != null && !text.trim().isEmpty()) out.add(text.trim());
            } else {
                out.add(one.toString());
            }
        }
        return out;
    }

    private JsonArray normalizeCharacterDrives(JsonElement element) {
        JsonArray out = new JsonArray();
        if (element == null || element.isJsonNull() || !element.isJsonArray()) return out;
        JsonArray arr = element.getAsJsonArray();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement one = arr.get(i);
            if (one == null || one.isJsonNull()) continue;
            JsonObject item = new JsonObject();
            if (one.isJsonObject()) {
                JsonObject src = one.getAsJsonObject();
                item.addProperty("name", getStringFlexible(src, "name"));
                item.addProperty("goal", getStringFlexible(src, "goal"));
                item.addProperty("misbelief", getStringFlexible(src, "misbelief"));
                item.addProperty("emotion", getStringFlexible(src, "emotion"));
            } else {
                String text = one.isJsonPrimitive() ? one.getAsString() : one.toString();
                item.addProperty("name", "");
                item.addProperty("goal", text != null ? text : "");
                item.addProperty("misbelief", "");
                item.addProperty("emotion", "");
            }
            out.add(item);
        }
        return out;
    }

    private String extractJsonObjectSlice(String text) {
        if (text == null || text.isEmpty()) return "";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return "";
        return text.substring(start, end + 1);
    }

    private JsonObject buildNoThinkingReasoning(String providerId, boolean localOpenAiCompat) {
        SessionChatOptions options = new SessionChatOptions();
        options.thinking = false;
        options.streamOutput = true;
        JsonObject reasoning = ProviderRequestOptionsBuilder.buildReasoningConfig(providerId, options);
        if (reasoning != null) return reasoning;
        if (!localOpenAiCompat) return null;
        JsonObject fallback = new JsonObject();
        fallback.addProperty("budget", 0);
        fallback.addProperty("format", "hide");
        return fallback;
    }

    private static class InlineThinkState {
        boolean inThink;
        String carry = "";
    }

    private static class ContentReasoningParts {
        final String content;
        final String reasoning;

        ContentReasoningParts(String content, String reasoning) {
            this.content = content != null ? content : "";
            this.reasoning = reasoning != null ? reasoning : "";
        }
    }

    private ContentReasoningParts splitInlineThink(String delta, InlineThinkState state, boolean flushTail) {
        if (state == null) {
            return new ContentReasoningParts(delta != null ? delta : "", "");
        }
        String chunk = delta != null ? delta : "";
        String input = state.carry + chunk;
        state.carry = "";
        if (input.isEmpty()) return new ContentReasoningParts("", "");

        int carryLen = flushTail ? 0 : computeThinkTagCarry(input);
        String parse = input.substring(0, input.length() - carryLen);
        if (!flushTail && carryLen > 0) {
            state.carry = input.substring(input.length() - carryLen);
        }

        StringBuilder outContent = new StringBuilder();
        StringBuilder outReasoning = new StringBuilder();
        int i = 0;
        final String openTag = "<think>";
        final String closeTag = "</think>";
        while (i < parse.length()) {
            if (state.inThink) {
                int close = indexOfIgnoreCase(parse, closeTag, i);
                if (close < 0) {
                    outReasoning.append(parse.substring(i));
                    i = parse.length();
                } else {
                    outReasoning.append(parse, i, close);
                    i = close + closeTag.length();
                    state.inThink = false;
                }
            } else {
                int open = indexOfIgnoreCase(parse, openTag, i);
                if (open < 0) {
                    outContent.append(parse.substring(i));
                    i = parse.length();
                } else {
                    outContent.append(parse, i, open);
                    i = open + openTag.length();
                    state.inThink = true;
                }
            }
        }

        if (flushTail && state.carry != null && !state.carry.isEmpty()) {
            if (state.inThink) outReasoning.append(state.carry);
            else outContent.append(state.carry);
            state.carry = "";
        }
        return new ContentReasoningParts(outContent.toString(), outReasoning.toString());
    }

    private int computeThinkTagCarry(String input) {
        if (input == null || input.isEmpty()) return 0;
        String lower = input.toLowerCase(java.util.Locale.ROOT);
        String[] tags = new String[] {"<think>", "</think>"};
        int best = 0;
        for (String tag : tags) {
            for (int len = 1; len < tag.length(); len++) {
                if (lower.endsWith(tag.substring(0, len))) {
                    if (len > best) best = len;
                }
            }
        }
        return best;
    }

    private int indexOfIgnoreCase(String text, String needle, int fromIndex) {
        if (text == null || needle == null) return -1;
        String lowerText = text.toLowerCase(java.util.Locale.ROOT);
        String lowerNeedle = needle.toLowerCase(java.util.Locale.ROOT);
        return lowerText.indexOf(lowerNeedle, Math.max(0, fromIndex));
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
