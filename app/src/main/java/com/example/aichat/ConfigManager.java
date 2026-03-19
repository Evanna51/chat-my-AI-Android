package com.example.aichat;

import android.content.Context;
import android.content.SharedPreferences;

public class ConfigManager {
    private static final String PREFS = "aichat_prefs";
    private static final String KEY_API_URL = "api_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model";
    private static final String KEY_THEME = "theme";           // light, dark, system
    private static final String KEY_THEME_COLOR = "theme_color"; // blue, green, purple, orange
    private static final String KEY_FONT_SIZE = "font_size";   // 12, 14, 16, 18, 20
    private static final String KEY_THREAD_NAMING_MODEL = "thread_naming_model";
    private static final String KEY_SEARCH_MODEL = "search_model";
    private static final String KEY_SUMMARY_MODEL = "summary_model";

    private static final String DEFAULT_API_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-3.5-turbo";
    private static final String DEFAULT_THEME = "system";
    private static final String DEFAULT_THEME_COLOR = "blue";
    private static final int DEFAULT_FONT_SIZE = 14;

    private final SharedPreferences prefs;

    public ConfigManager(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getApiUrl() {
        return prefs.getString(KEY_API_URL, DEFAULT_API_URL);
    }

    public void setApiUrl(String url) {
        prefs.edit().putString(KEY_API_URL, url).apply();
    }

    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }

    public void setApiKey(String key) {
        prefs.edit().putString(KEY_API_KEY, key).apply();
    }

    public String getModel() {
        return prefs.getString(KEY_MODEL, DEFAULT_MODEL);
    }

    public void setModel(String model) {
        prefs.edit().putString(KEY_MODEL, model).apply();
    }

    public void save(String apiUrl, String apiKey, String model) {
        prefs.edit()
                .putString(KEY_API_URL, apiUrl)
                .putString(KEY_API_KEY, apiKey)
                .putString(KEY_MODEL, model)
                .apply();
    }

    public String getTheme() { return prefs.getString(KEY_THEME, DEFAULT_THEME); }
    public void setTheme(String theme) { prefs.edit().putString(KEY_THEME, theme).apply(); }

    public String getThemeColor() { return prefs.getString(KEY_THEME_COLOR, DEFAULT_THEME_COLOR); }
    public void setThemeColor(String color) { prefs.edit().putString(KEY_THEME_COLOR, color).apply(); }

    public int getFontSize() { return prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE); }
    public void setFontSize(int size) { prefs.edit().putInt(KEY_FONT_SIZE, size).apply(); }

    public String getThreadNamingModel() { return prefs.getString(KEY_THREAD_NAMING_MODEL, getModel()); }
    public void setThreadNamingModel(String m) { prefs.edit().putString(KEY_THREAD_NAMING_MODEL, m).apply(); }

    public String getSearchModel() { return prefs.getString(KEY_SEARCH_MODEL, getModel()); }
    public void setSearchModel(String m) { prefs.edit().putString(KEY_SEARCH_MODEL, m).apply(); }

    public String getSummaryModel() { return prefs.getString(KEY_SUMMARY_MODEL, getModel()); }
    public void setSummaryModel(String m) { prefs.edit().putString(KEY_SUMMARY_MODEL, m).apply(); }
}
