package com.example.aichat;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 获取 OpenAI 兼容 API 的模型列表，参考 chatbox fetchRemoteModels
 */
public class ModelsFetcher {
    private static final Gson GSON = new Gson();
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
                String url = base + (base.endsWith("/") ? "" : "/") + "models";
                Request req = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                        .get()
                        .build();
                Response resp = CLIENT.newCall(req).execute();
                if (!resp.isSuccessful() || resp.body() == null) {
                    String err = resp.body() != null ? resp.body().string() : "HTTP " + resp.code();
                    postError(callback, err);
                    return;
                }
                String body = resp.body().string();
                JsonObject json = GSON.fromJson(body, JsonObject.class);
                JsonArray data = json != null && json.has("data") ? json.getAsJsonArray("data") : null;
                List<ProviderInfo.ProviderModelInfo> models = new ArrayList<>();
                if (data != null) {
                    for (int i = 0; i < data.size(); i++) {
                        JsonObject item = data.get(i).getAsJsonObject();
                        String id = item.has("id") ? item.get("id").getAsString() : null;
                        if (id != null && !id.isEmpty()) {
                            models.add(new ProviderInfo.ProviderModelInfo(id));
                        }
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
}
