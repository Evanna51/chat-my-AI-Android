package com.example.aichat.sync

import android.content.Context
import com.example.aichat.ModelConfig

/**
 * SharedPreferences-backed config for the wi-chat-server remote service feature.
 *
 * Stores TWO server profiles — HOME (居家) and AWAY (外出) — each with its own
 * baseUrl + apiKey. The **active mode is NOT owned here** — it follows
 * [ModelConfig.getActiveScene] so that switching the model-config scene
 * (manually in 模型配置, or programmatically by [SyncScheduler] auto-switch)
 * also switches the remote service endpoint. [ModelConfig.Scene.CUSTOM]
 * falls back to HOME's URL (only two URLs are exposed to the user).
 *
 * baseUrl example: `http://192.168.5.7:8787` (no trailing slash)
 */
class RemoteSyncConfigStore(context: Context) {

    enum class Mode(val key: String) {
        HOME("home"), AWAY("away");

        fun toScene(): ModelConfig.Scene = when (this) {
            HOME -> ModelConfig.Scene.HOME
            AWAY -> ModelConfig.Scene.AWAY
        }

        companion object {
            fun fromScene(scene: ModelConfig.Scene): Mode = when (scene) {
                ModelConfig.Scene.AWAY -> AWAY
                // CUSTOM has no remote URL of its own — share HOME.
                ModelConfig.Scene.HOME, ModelConfig.Scene.CUSTOM -> HOME
            }
        }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val modelConfig: ModelConfig by lazy { ModelConfig(appContext) }

    init {
        migrateFromLegacyCharacterMemoryStoreIfNeeded()
        migrateToDualModeIfNeeded()
    }

    /**
     * One-shot migration: when the new wi_sync store is empty but the old
     * `character_memory` SP has values (from before the merger), copy them
     * over so the user doesn't have to re-enter baseUrl / apiKey.
     */
    private fun migrateFromLegacyCharacterMemoryStoreIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED_FROM_CHARACTER_MEMORY, false)) return
        val legacy = appContext.getSharedPreferences("character_memory", Context.MODE_PRIVATE)
        val legacyBaseUrl = legacy.getString("base_url", "")?.trim()?.trimEnd('/') ?: ""
        val legacyApiKey = legacy.getString("api_key", "")?.trim() ?: ""
        val legacyEnabled = legacy.getBoolean("enabled", false)
        val editor = prefs.edit()
        if (prefs.getString(KEY_BASE_URL_LEGACY, "").isNullOrEmpty() && legacyBaseUrl.isNotEmpty()) {
            editor.putString(KEY_BASE_URL_LEGACY, legacyBaseUrl)
        }
        if (prefs.getString(KEY_API_KEY_LEGACY, "").isNullOrEmpty() && legacyApiKey.isNotEmpty()) {
            editor.putString(KEY_API_KEY_LEGACY, legacyApiKey)
        }
        if (!prefs.contains(KEY_ENABLED) && legacyEnabled) {
            editor.putBoolean(KEY_ENABLED, true)
        }
        editor.putBoolean(KEY_MIGRATED_FROM_CHARACTER_MEMORY, true).apply()
    }

    /**
     * Migrate single-config (`base_url` / `api_key`) into the home profile.
     * Idempotent.
     */
    private fun migrateToDualModeIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED_DUAL_MODE, false)) return
        val legacyBase = prefs.getString(KEY_BASE_URL_LEGACY, "")?.trim()?.trimEnd('/') ?: ""
        val legacyKey = prefs.getString(KEY_API_KEY_LEGACY, "")?.trim() ?: ""
        val editor = prefs.edit()
        if (prefs.getString(keyBaseUrl(Mode.HOME), "").isNullOrEmpty() && legacyBase.isNotEmpty()) {
            editor.putString(keyBaseUrl(Mode.HOME), legacyBase)
        }
        if (prefs.getString(keyApiKey(Mode.HOME), "").isNullOrEmpty() && legacyKey.isNotEmpty()) {
            editor.putString(keyApiKey(Mode.HOME), legacyKey)
        }
        editor.putBoolean(KEY_MIGRATED_DUAL_MODE, true).apply()
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    // --- Active mode (follows ModelConfig.activeScene; no separate storage) ---

    /** Active mode = whatever ModelConfig says, with CUSTOM → HOME. */
    fun getMode(): Mode = Mode.fromScene(modelConfig.getActiveScene())

    /**
     * Switch the active mode by writing to ModelConfig (the single source of
     * truth). Returns true if the scene actually changed. Auto-switch in
     * [SyncScheduler] goes through this so 模型配置 stays in sync.
     */
    fun setMode(mode: Mode): Boolean {
        val before = modelConfig.getActiveScene()
        val target = mode.toScene()
        if (before == target) return false
        modelConfig.setActiveScene(target)
        return true
    }

    // --- Active-mode shortcuts (preserve old API for existing callers) ---

    fun getBaseUrl(): String = getBaseUrl(getMode())
    fun setBaseUrl(value: String?) = setBaseUrl(getMode(), value)

    fun getApiKey(): String = getApiKey(getMode())
    fun setApiKey(value: String?) = setApiKey(getMode(), value)

    fun isReady(): Boolean = isEnabled() && getBaseUrl().isNotEmpty()

    // --- Per-mode getters/setters ---

    fun getBaseUrl(mode: Mode): String =
        prefs.getString(keyBaseUrl(mode), "")?.trimEnd('/') ?: ""

    fun setBaseUrl(mode: Mode, value: String?) =
        prefs.edit().putString(keyBaseUrl(mode), value?.trim()?.trimEnd('/') ?: "").apply()

    fun getApiKey(mode: Mode): String =
        prefs.getString(keyApiKey(mode), "") ?: ""

    fun setApiKey(mode: Mode, value: String?) =
        prefs.edit().putString(keyApiKey(mode), value?.trim() ?: "").apply()

    fun isModeConfigured(mode: Mode): Boolean = getBaseUrl(mode).isNotEmpty()

    // --- Status ---

    fun getLastSyncAt(): Long = prefs.getLong(KEY_LAST_SYNC_AT, 0L)
    fun setLastSyncAt(ts: Long) = prefs.edit().putLong(KEY_LAST_SYNC_AT, ts).apply()

    fun getLastError(): String = prefs.getString(KEY_LAST_ERROR, "") ?: ""
    fun setLastError(msg: String?) = prefs.edit().putString(KEY_LAST_ERROR, msg ?: "").apply()

    /** Whether the LLM should be offered the search_memory tool during chat. */
    fun isSearchMemoryToolEnabled(): Boolean =
        prefs.getBoolean(KEY_TOOL_SEARCH_MEMORY, false)
            && getBaseUrl().isNotEmpty()

    fun setSearchMemoryToolEnabled(value: Boolean) =
        prefs.edit().putBoolean(KEY_TOOL_SEARCH_MEMORY, value).apply()

    private fun keyBaseUrl(mode: Mode) = "base_url_${mode.key}"
    private fun keyApiKey(mode: Mode) = "api_key_${mode.key}"

    companion object {
        private const val PREFS = "wi_sync"
        private const val KEY_ENABLED = "enabled"
        // Legacy single-config keys, kept for migration only.
        private const val KEY_BASE_URL_LEGACY = "base_url"
        private const val KEY_API_KEY_LEGACY = "api_key"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_TOOL_SEARCH_MEMORY = "tool_search_memory_enabled"
        private const val KEY_MIGRATED_FROM_CHARACTER_MEMORY = "migrated_from_character_memory"
        private const val KEY_MIGRATED_DUAL_MODE = "migrated_dual_mode"
    }
}
