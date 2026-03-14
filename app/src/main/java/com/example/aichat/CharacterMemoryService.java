package com.example.aichat;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.net.URLEncoder;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class CharacterMemoryService {
    private static final String TAG = "CharacterMemoryService";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Gson GSON = new Gson();

    private final CharacterMemoryConfigStore configStore;

    public CharacterMemoryService(Context context) {
        this.configStore = new CharacterMemoryConfigStore(context);
    }

    public boolean isEnabled() {
        return configStore.isEnabled();
    }

    public CharacterMemoryApi.MemoryContextResponse getMemoryContext(
            String assistantId,
            String sessionId,
            String userMessage
    ) throws Exception {
        CharacterMemoryApi.MemoryContextRequest body = new CharacterMemoryApi.MemoryContextRequest();
        body.assistantId = safeTrim(assistantId);
        body.sessionId = safeTrim(sessionId);
        String safeInput = safeTrim(userMessage);
        body.userInput = safeInput;
        body.userMessage = safeInput;

        String raw = postJson(CharacterMemoryApi.PATH_MEMORY_CONTEXT, GSON.toJson(body));
        return parseMemoryContextResponse(raw);
    }

    public void reportInteraction(
            String assistantId,
            String sessionId,
            String role,
            String content
    ) throws Exception {
        CharacterMemoryApi.ReportInteractionRequest body = new CharacterMemoryApi.ReportInteractionRequest();
        body.assistantId = safeTrim(assistantId);
        body.sessionId = safeTrim(sessionId);
        body.role = safeTrim(role);
        body.content = safeTrim(content);
        postJson(CharacterMemoryApi.PATH_REPORT_INTERACTION, GSON.toJson(body));
    }

    public void reportCharacterProfile(
            String assistantId,
            String characterName,
            String characterBackground,
            boolean allowAutoLife,
            boolean allowProactiveMessage
    ) throws Exception {
        CharacterMemoryApi.CharacterProfileRequest body = new CharacterMemoryApi.CharacterProfileRequest();
        body.assistantId = safeTrim(assistantId);
        body.characterName = safeTrim(characterName);
        body.characterBackground = safeTrim(characterBackground);
        body.allowAutoLife = allowAutoLife;
        body.allowProactiveMessage = allowProactiveMessage;
        postJson(CharacterMemoryApi.PATH_REPORT_CHARACTER_PROFILE, GSON.toJson(body));
    }

    public CharacterMemoryApi.PullMessagesResponse pullMessages(String since, int limit) throws Exception {
        StringBuilder query = new StringBuilder();
        query.append("userId=default-user");
        if (since != null && !since.trim().isEmpty()) {
            query.append("since=").append(urlEncode(since.trim()));
        }
        if (limit > 0) {
            if (query.length() > 0) query.append("&");
            query.append("limit=").append(limit);
        }
        String raw = getJson(CharacterMemoryApi.PATH_PULL_MESSAGES, query.toString());
        return parsePullMessagesResponse(raw);
    }

    public CharacterMemoryApi.AckMessageResponse ackMessage(String messageId, String ackStatus) throws Exception {
        CharacterMemoryApi.AckMessageRequest body = new CharacterMemoryApi.AckMessageRequest();
        body.messageId = safeTrim(messageId);
        body.ackStatus = safeTrim(ackStatus);
        String raw = postJson(CharacterMemoryApi.PATH_ACK_MESSAGE, GSON.toJson(body));
        return parseAckMessageResponse(raw);
    }

    private String postJson(String path, String jsonBody) throws Exception {
        return executeJsonRequest("POST", path, null, jsonBody);
    }

    private String getJson(String path, String queryString) throws Exception {
        return executeJsonRequest("GET", path, queryString, null);
    }

    private String executeJsonRequest(String method, String path, String queryString, String jsonBody) throws Exception {
        String baseUrl = normalizeBaseUrl(configStore.getBaseUrl());
        String apiKey = safeTrim(configStore.getApiKey());
        String url = baseUrl + path;
        if (queryString != null && !queryString.trim().isEmpty()) {
            url = url + "?" + queryString.trim();
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(configStore.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(configStore.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(configStore.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();

        Request.Builder requestBuilder = new Request.Builder().url(url);
        if ("POST".equalsIgnoreCase(method)) {
            requestBuilder.post(RequestBody.create(jsonBody != null ? jsonBody : "{}", JSON));
            requestBuilder.addHeader("Content-Type", "application/json");
        } else {
            requestBuilder.get();
        }
        if (!apiKey.isEmpty()) {
            requestBuilder.addHeader("x-api-key", apiKey);
        }
        Request request = requestBuilder.build();
        try (Response response = client.newCall(request).execute()) {
            String body = "";
            try (ResponseBody rb = response.body()) {
                body = rb != null ? rb.string() : "";
            }
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code() + ": " + body);
            }
            if (configStore.isDebugLogEnabled()) {
                Log.d(TAG, method + " " + path + " ok, response=" + body);
            }
            return body;
        }
    }

    private CharacterMemoryApi.MemoryContextResponse parseMemoryContextResponse(String raw) {
        CharacterMemoryApi.MemoryContextResponse out = new CharacterMemoryApi.MemoryContextResponse();
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JsonObject obj = new JsonParser().parse(raw).getAsJsonObject();
            out.ok = getBoolean(obj, "ok");
            out.shouldUseMemory = getBoolean(obj, "shouldUseMemory");
            out.reason = getString(obj, "reason");
            out.memoryGuidance = getString(obj, "memoryGuidance");

            JsonArray lines = obj.has("memoryLines") && obj.get("memoryLines").isJsonArray()
                    ? obj.getAsJsonArray("memoryLines") : null;
            if (lines != null) {
                List<String> parsed = new ArrayList<>();
                for (JsonElement one : lines) {
                    if (one == null || one.isJsonNull()) continue;
                    String line = one.getAsString();
                    if (line != null && !line.trim().isEmpty()) parsed.add(line.trim());
                }
                out.memoryLines = parsed;
            }
            if ((out.memoryGuidance == null || out.memoryGuidance.trim().isEmpty())
                    && out.memoryLines != null && !out.memoryLines.isEmpty()) {
                out.memoryGuidance = "记忆参考: " + joinWithSeparator(out.memoryLines, " | ");
            }
        } catch (Exception ignored) {
            out.reason = "invalid_json";
        }
        return out;
    }

    private CharacterMemoryApi.PullMessagesResponse parsePullMessagesResponse(String raw) {
        CharacterMemoryApi.PullMessagesResponse out = new CharacterMemoryApi.PullMessagesResponse();
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JsonObject obj = new JsonParser().parse(raw).getAsJsonObject();
            out.ok = getBoolean(obj, "ok");
            out.userId = getString(obj, "userId");
            out.since = getString(obj, "since");
            out.count = getInt(obj, "count");
            out.now = getString(obj, "now");
            JsonArray arr = obj.has("messages") && obj.get("messages").isJsonArray()
                    ? obj.getAsJsonArray("messages") : null;
            if (arr != null) {
                List<CharacterMemoryApi.PulledMessage> list = new ArrayList<>();
                for (JsonElement one : arr) {
                    if (one == null || !one.isJsonObject()) continue;
                    JsonObject item = one.getAsJsonObject();
                    CharacterMemoryApi.PulledMessage m = new CharacterMemoryApi.PulledMessage();
                    m.id = getString(item, "id");
                    m.assistantId = getString(item, "assistantId");
                    m.sessionId = getString(item, "sessionId");
                    m.messageType = getString(item, "messageType");
                    m.title = getString(item, "title");
                    m.body = getString(item, "body");
                    m.payload = getJsonObject(item, "payload");
                    m.createdAt = getString(item, "createdAt");
                    m.availableAt = getString(item, "availableAt");
                    m.expiresAt = getString(item, "expiresAt");
                    m.pullCount = getInt(item, "pullCount");
                    list.add(m);
                }
                out.messages = list;
                if (out.count <= 0) out.count = list.size();
            }
        } catch (Exception ignored) {}
        return out;
    }

    private CharacterMemoryApi.AckMessageResponse parseAckMessageResponse(String raw) {
        CharacterMemoryApi.AckMessageResponse out = new CharacterMemoryApi.AckMessageResponse();
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JsonObject obj = new JsonParser().parse(raw).getAsJsonObject();
            out.ok = getBoolean(obj, "ok");
            out.messageId = getString(obj, "messageId");
            out.ackStatus = getString(obj, "ackStatus");
        } catch (Exception ignored) {}
        return out;
    }

    private String normalizeBaseUrl(String source) {
        String base = safeTrim(source);
        if (base.isEmpty()) base = "http://127.0.0.1:8787";
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "http://" + base;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private String safeTrim(String text) {
        return text != null ? text.trim() : "";
    }

    private boolean getBoolean(JsonObject obj, String key) {
        try {
            JsonElement e = obj.get(key);
            if (e == null || e.isJsonNull()) return false;
            return e.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private int getInt(JsonObject obj, String key) {
        try {
            JsonElement e = obj.get(key);
            if (e == null || e.isJsonNull()) return 0;
            return e.getAsInt();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String getString(JsonObject obj, String key) {
        try {
            JsonElement e = obj.get(key);
            if (e == null || e.isJsonNull()) return "";
            return e.getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private JsonObject getJsonObject(JsonObject obj, String key) {
        try {
            JsonElement e = obj.get(key);
            if (e == null || e.isJsonNull() || !e.isJsonObject()) return new JsonObject();
            return e.getAsJsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private String joinWithSeparator(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private String urlEncode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (Exception ignored) {
            return text;
        }
    }
}
