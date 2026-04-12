package com.example.aichat

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class ConfigManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getApiUrl(): String = prefs.getString(KEY_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
    fun setApiUrl(url: String) = prefs.edit { putString(KEY_API_URL, url) }

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""
    fun setApiKey(key: String) = prefs.edit { putString(KEY_API_KEY, key) }

    fun getModel(): String = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun setModel(model: String) = prefs.edit { putString(KEY_MODEL, model) }

    fun save(apiUrl: String, apiKey: String, model: String) {
        prefs.edit {
            putString(KEY_API_URL, apiUrl)
            putString(KEY_API_KEY, apiKey)
            putString(KEY_MODEL, model)
        }
    }

    fun getTheme(): String = prefs.getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME
    fun setTheme(theme: String) = prefs.edit { putString(KEY_THEME, theme) }

    fun getThemeColor(): String = prefs.getString(KEY_THEME_COLOR, DEFAULT_THEME_COLOR) ?: DEFAULT_THEME_COLOR
    fun setThemeColor(color: String) = prefs.edit { putString(KEY_THEME_COLOR, color) }

    fun getFontSize(): Int = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
    fun setFontSize(size: Int) = prefs.edit { putInt(KEY_FONT_SIZE, size) }

    fun getThreadNamingModel(): String = prefs.getString(KEY_THREAD_NAMING_MODEL, getModel()) ?: getModel()
    fun setThreadNamingModel(m: String) = prefs.edit { putString(KEY_THREAD_NAMING_MODEL, m) }

    fun getSearchModel(): String = prefs.getString(KEY_SEARCH_MODEL, getModel()) ?: getModel()
    fun setSearchModel(m: String) = prefs.edit { putString(KEY_SEARCH_MODEL, m) }

    fun getSummaryModel(): String = prefs.getString(KEY_SUMMARY_MODEL, getModel()) ?: getModel()
    fun setSummaryModel(m: String) = prefs.edit { putString(KEY_SUMMARY_MODEL, m) }

    companion object {
        private const val PREFS = "aichat_prefs"
        private const val KEY_API_URL = "api_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_THEME = "theme"           // light, dark, system
        private const val KEY_THEME_COLOR = "theme_color" // blue, green, purple, orange
        private const val KEY_FONT_SIZE = "font_size"   // 12, 14, 16, 18, 20
        private const val KEY_THREAD_NAMING_MODEL = "thread_naming_model"
        private const val KEY_SEARCH_MODEL = "search_model"
        private const val KEY_SUMMARY_MODEL = "summary_model"

        private const val DEFAULT_API_URL = "https://api.openai.com/v1"
        private const val DEFAULT_MODEL = "gpt-3.5-turbo"
        private const val DEFAULT_THEME = "system"
        private const val DEFAULT_THEME_COLOR = "blue"
        private const val DEFAULT_FONT_SIZE = 14
    }
}
