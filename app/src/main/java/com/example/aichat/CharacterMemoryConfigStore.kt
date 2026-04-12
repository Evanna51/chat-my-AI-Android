package com.example.aichat

import android.content.Context
import android.content.SharedPreferences

class CharacterMemoryConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getBaseUrl(): String = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun setBaseUrl(baseUrl: String?) {
        prefs.edit().putString(KEY_BASE_URL, baseUrl?.trim() ?: "").apply()
    }

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""

    fun setApiKey(apiKey: String?) {
        prefs.edit().putString(KEY_API_KEY, apiKey?.trim() ?: "").apply()
    }

    fun getConnectTimeoutMs(): Int = clampTimeout(prefs.getInt(KEY_CONNECT_TIMEOUT_MS, DEFAULT_CONNECT_TIMEOUT_MS))

    fun setConnectTimeoutMs(timeoutMs: Int) {
        prefs.edit().putInt(KEY_CONNECT_TIMEOUT_MS, clampTimeout(timeoutMs)).apply()
    }

    fun getReadTimeoutMs(): Int = clampTimeout(prefs.getInt(KEY_READ_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS))

    fun setReadTimeoutMs(timeoutMs: Int) {
        prefs.edit().putInt(KEY_READ_TIMEOUT_MS, clampTimeout(timeoutMs)).apply()
    }

    fun isDebugLogEnabled(): Boolean = prefs.getBoolean(KEY_DEBUG_LOG_ENABLED, false)

    fun setDebugLogEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_LOG_ENABLED, enabled).apply()
    }

    fun saveAll(
        enabled: Boolean,
        baseUrl: String?,
        apiKey: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        debugLogEnabled: Boolean
    ) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_BASE_URL, baseUrl?.trim() ?: "")
            .putString(KEY_API_KEY, apiKey?.trim() ?: "")
            .putInt(KEY_CONNECT_TIMEOUT_MS, clampTimeout(connectTimeoutMs))
            .putInt(KEY_READ_TIMEOUT_MS, clampTimeout(readTimeoutMs))
            .putBoolean(KEY_DEBUG_LOG_ENABLED, debugLogEnabled)
            .apply()
    }

    private fun clampTimeout(value: Int): Int {
        if (value < 1000) return 1000
        return minOf(value, 30000)
    }

    companion object {
        private const val PREFS = "aichat_character_memory_config"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_CONNECT_TIMEOUT_MS = "connect_timeout_ms"
        private const val KEY_READ_TIMEOUT_MS = "read_timeout_ms"
        private const val KEY_DEBUG_LOG_ENABLED = "debug_log_enabled"
        private const val DEFAULT_BASE_URL = "http://127.0.0.1:8787"
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 5000
        private const val DEFAULT_READ_TIMEOUT_MS = 8000
    }
}
