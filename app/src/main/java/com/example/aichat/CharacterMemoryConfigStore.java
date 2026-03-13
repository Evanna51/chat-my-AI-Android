package com.example.aichat;

import android.content.Context;
import android.content.SharedPreferences;

public class CharacterMemoryConfigStore {
    private static final String PREFS = "aichat_character_memory_config";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_CONNECT_TIMEOUT_MS = "connect_timeout_ms";
    private static final String KEY_READ_TIMEOUT_MS = "read_timeout_ms";
    private static final String KEY_DEBUG_LOG_ENABLED = "debug_log_enabled";

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8787";
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 8000;

    private final SharedPreferences prefs;

    public CharacterMemoryConfigStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public String getBaseUrl() {
        return prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL);
    }

    public void setBaseUrl(String baseUrl) {
        prefs.edit().putString(KEY_BASE_URL, baseUrl != null ? baseUrl.trim() : "").apply();
    }

    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }

    public void setApiKey(String apiKey) {
        prefs.edit().putString(KEY_API_KEY, apiKey != null ? apiKey.trim() : "").apply();
    }

    public int getConnectTimeoutMs() {
        return clampTimeout(prefs.getInt(KEY_CONNECT_TIMEOUT_MS, DEFAULT_CONNECT_TIMEOUT_MS));
    }

    public void setConnectTimeoutMs(int timeoutMs) {
        prefs.edit().putInt(KEY_CONNECT_TIMEOUT_MS, clampTimeout(timeoutMs)).apply();
    }

    public int getReadTimeoutMs() {
        return clampTimeout(prefs.getInt(KEY_READ_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS));
    }

    public void setReadTimeoutMs(int timeoutMs) {
        prefs.edit().putInt(KEY_READ_TIMEOUT_MS, clampTimeout(timeoutMs)).apply();
    }

    public boolean isDebugLogEnabled() {
        return prefs.getBoolean(KEY_DEBUG_LOG_ENABLED, false);
    }

    public void setDebugLogEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DEBUG_LOG_ENABLED, enabled).apply();
    }

    public void saveAll(boolean enabled,
                        String baseUrl,
                        String apiKey,
                        int connectTimeoutMs,
                        int readTimeoutMs,
                        boolean debugLogEnabled) {
        prefs.edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_BASE_URL, baseUrl != null ? baseUrl.trim() : "")
                .putString(KEY_API_KEY, apiKey != null ? apiKey.trim() : "")
                .putInt(KEY_CONNECT_TIMEOUT_MS, clampTimeout(connectTimeoutMs))
                .putInt(KEY_READ_TIMEOUT_MS, clampTimeout(readTimeoutMs))
                .putBoolean(KEY_DEBUG_LOG_ENABLED, debugLogEnabled)
                .apply();
    }

    private int clampTimeout(int value) {
        if (value < 1000) return 1000;
        return Math.min(value, 30000);
    }
}
