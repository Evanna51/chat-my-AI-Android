package com.example.aichat;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 获取 OpenAI 兼容 API 的模型列表，参考 chatbox fetchRemoteModels
 */
public class ModelsFetcher {
    private static final Gson GSON = new Gson();
    private static final int MAX_MODEL_PAGE_FETCH = 30;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onSuccess(List<ProviderInfo.ProviderModelInfo> models);
        void onError(String message);
    }

    public static void fetch(String apiHost, String apiPath, String apiKey, Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                String base = ApiUtils.toModelsBaseUrl(apiHost, apiPath);
                String requestUrl = buildModelsUrl(base, null);
                String nextAfter = null;
                Set<String> fetchedRequestKeys = new HashSet<>();
                Set<String> seenModelIds = new HashSet<>();
                List<ProviderInfo.ProviderModelInfo> models = new ArrayList<>();

                for (int i = 0; i < MAX_MODEL_PAGE_FETCH && requestUrl != null; i++) {
                    if (!fetchedRequestKeys.add(requestUrl)) {
                        break;
                    }
                    Request req = new Request.Builder()
                            .url(requestUrl)
                            .addHeader("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                            .get()
                            .build();
                    PageResult result;
                    try (Response resp = CLIENT.newCall(req).execute()) {
                        if (!resp.isSuccessful() || resp.body() == null) {
                            String err = resp.body() != null ? resp.body().string() : "HTTP " + resp.code();
                            postError(callback, err);
                            return;
                        }
                        result = parsePage(resp.body().string());
                    }
                    for (String id : result.modelIds) {
                        if (seenModelIds.add(id)) {
                            models.add(new ProviderInfo.ProviderModelInfo(id));
                        }
                    }

                    if (result.nextUrl != null && !result.nextUrl.isEmpty()) {
                        requestUrl = result.nextUrl;
                        continue;
                    }

                    nextAfter = result.nextCursor;
                    if ((nextAfter == null || nextAfter.isEmpty()) && result.hasMore) {
                        nextAfter = result.lastId;
                    }
                    if (nextAfter == null || nextAfter.isEmpty()) {
                        requestUrl = null;
                    } else {
                        requestUrl = buildModelsUrl(base, nextAfter);
                    }
                }
                List<ProviderInfo.ProviderModelInfo> finalList = models;
                MAIN.post(() -> callback.onSuccess(finalList));
            } catch (IOException e) {
                postError(callback, e.getMessage());
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    private static void postError(Callback callback, String msg) {
        MAIN.post(() -> callback.onError(msg != null ? msg : "Unknown error"));
    }

    private static String buildModelsUrl(String base, String after) {
        String url = base + (base.endsWith("/") ? "" : "/") + "models";
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) return url;
        HttpUrl.Builder builder = parsed.newBuilder();
        if (after != null && !after.isEmpty()) {
            builder.addQueryParameter("after", after);
        }
        return builder.build().toString();
    }

    private static PageResult parsePage(String body) {
        JsonObject json = GSON.fromJson(body, JsonObject.class);
        PageResult page = new PageResult();
        JsonArray data = json != null && json.has("data") ? json.getAsJsonArray("data") : null;
        if (data != null) {
            for (int i = 0; i < data.size(); i++) {
                JsonObject item = data.get(i).getAsJsonObject();
                String id = getString(item, "id");
                if (id != null && !id.isEmpty()) {
                    page.modelIds.add(id);
                    page.lastId = id;
                }
            }
        }
        if (json != null) {
            page.hasMore = getBoolean(json, "has_more", "hasMore");
            page.nextCursor = getString(json, "next_cursor", "nextCursor", "after", "cursor");
            String next = getString(json, "next");
            if (next != null && (next.startsWith("http://") || next.startsWith("https://"))) {
                page.nextUrl = next;
            } else if ((page.nextCursor == null || page.nextCursor.isEmpty()) && next != null && !next.isEmpty()) {
                page.nextCursor = next;
            }
        }
        return page;
    }

    private static String getString(JsonObject obj, String... keys) {
        if (obj == null || keys == null) return null;
        for (String key : keys) {
            if (key == null || !obj.has(key)) continue;
            JsonElement element = obj.get(key);
            if (element == null || element.isJsonNull()) continue;
            String value = element.getAsString();
            if (value != null && !value.isEmpty()) return value;
        }
        return null;
    }

    private static boolean getBoolean(JsonObject obj, String... keys) {
        if (obj == null || keys == null) return false;
        for (String key : keys) {
            if (key == null || !obj.has(key)) continue;
            JsonElement element = obj.get(key);
            if (element == null || element.isJsonNull()) continue;
            try {
                return element.getAsBoolean();
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static class PageResult {
        List<String> modelIds = new ArrayList<>();
        String nextCursor;
        String nextUrl;
        String lastId;
        boolean hasMore;
    }
}
