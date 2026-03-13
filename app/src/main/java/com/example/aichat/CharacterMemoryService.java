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

    private String postJson(String path, String jsonBody) throws Exception {
        String baseUrl = normalizeBaseUrl(configStore.getBaseUrl());
        String apiKey = safeTrim(configStore.getApiKey());
        String url = baseUrl + path;

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(configStore.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(configStore.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(configStore.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonBody != null ? jsonBody : "{}", JSON))
                .addHeader("Content-Type", "application/json");
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
                Log.d(TAG, "POST " + path + " ok, response=" + body);
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

    private String getString(JsonObject obj, String key) {
        try {
            JsonElement e = obj.get(key);
            if (e == null || e.isJsonNull()) return "";
            return e.getAsString();
        } catch (Exception ignored) {
            return "";
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
}
