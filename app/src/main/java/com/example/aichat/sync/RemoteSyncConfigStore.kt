package com.example.aichat.sync

import android.content.Context

/**
 * SharedPreferences-backed config for the wi-chat-server remote sync feature.
 *
 * baseUrl example: `http://192.168.5.7:8787` (no trailing slash)
 */
class RemoteSyncConfigStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    fun getBaseUrl(): String = prefs.getString(KEY_BASE_URL, "")?.trimEnd('/') ?: ""
    fun setBaseUrl(value: String?) =
        prefs.edit().putString(KEY_BASE_URL, value?.trim()?.trimEnd('/') ?: "").apply()

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""
    fun setApiKey(value: String?) = prefs.edit().putString(KEY_API_KEY, value?.trim() ?: "").apply()

    fun getLastSyncAt(): Long = prefs.getLong(KEY_LAST_SYNC_AT, 0L)
    fun setLastSyncAt(ts: Long) = prefs.edit().putLong(KEY_LAST_SYNC_AT, ts).apply()

    fun getLastError(): String = prefs.getString(KEY_LAST_ERROR, "") ?: ""
    fun setLastError(msg: String?) = prefs.edit().putString(KEY_LAST_ERROR, msg ?: "").apply()

    fun isReady(): Boolean = isEnabled() && getBaseUrl().isNotEmpty() && getApiKey().isNotEmpty()

    /** Whether the LLM should be offered the search_memory tool during chat. */
    fun isSearchMemoryToolEnabled(): Boolean =
        prefs.getBoolean(KEY_TOOL_SEARCH_MEMORY, false)
            && getBaseUrl().isNotEmpty()
            && getApiKey().isNotEmpty()

    fun setSearchMemoryToolEnabled(value: Boolean) =
        prefs.edit().putBoolean(KEY_TOOL_SEARCH_MEMORY, value).apply()

    companion object {
        private const val PREFS = "wi_sync"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_TOOL_SEARCH_MEMORY = "tool_search_memory_enabled"
    }
}
