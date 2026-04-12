package com.example.aichat

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 模型配置：各任务选用的模型预设。
 * 模型配置页（ModelConfigActivity）修改选用值；
 * 聊天等实际调用从 AiModelConfig 读取，AiModelConfig 从此处取预设的 modelKey。
 */
class ModelConfig(context: Context) {
    private val context: Context = context.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 对话选用的预设 (providerId:modelId) */
    fun getChatPreset(): String =
        if (isHomeModeEnabled()) getHomeChatPreset() else getAwayChatPreset()

    fun setChatPreset(modelKey: String?) {
        if (isHomeModeEnabled()) setHomeChatPreset(modelKey) else setAwayChatPreset(modelKey)
        if (!modelKey.isNullOrEmpty()) put(KEY_PRIMARY, modelKey)
    }

    /** 话题命名选用的预设 */
    fun getThreadNamingPreset(): String =
        if (isHomeModeEnabled()) getHomeThreadNamingPreset() else getAwayThreadNamingPreset()

    fun setThreadNamingPreset(modelKey: String?) {
        if (isHomeModeEnabled()) setHomeThreadNamingPreset(modelKey) else setAwayThreadNamingPreset(modelKey)
    }

    /** 搜索选用的预设 */
    fun getSearchPreset(): String =
        if (isHomeModeEnabled()) getHomeSearchPreset() else getAwaySearchPreset()

    fun setSearchPreset(modelKey: String?) {
        if (isHomeModeEnabled()) setHomeSearchPreset(modelKey) else setAwaySearchPreset(modelKey)
    }

    /** 总结选用的预设 */
    fun getSummaryPreset(): String =
        if (isHomeModeEnabled()) getHomeSummaryPreset() else getAwaySummaryPreset()

    fun setSummaryPreset(modelKey: String?) {
        if (isHomeModeEnabled()) setHomeSummaryPreset(modelKey) else setAwaySummaryPreset(modelKey)
    }

    /** 小说敏锐选用的预设 */
    fun getNovelSharpPreset(): String {
        val home = isHomeModeEnabled()
        val current = if (home) prefs.getString(KEY_NOVEL_SHARP, "") else prefs.getString(KEY_NOVEL_SHARP_AWAY, "")
        if (!current.isNullOrEmpty()) return current

        // Fallback to the other scene's novel preset first, then primary.
        val other = if (home) prefs.getString(KEY_NOVEL_SHARP_AWAY, "") else prefs.getString(KEY_NOVEL_SHARP, "")
        if (!other.isNullOrEmpty()) return other
        return getPrimaryPreset()
    }

    fun setNovelSharpPreset(modelKey: String?) {
        if (isHomeModeEnabled()) setHomeNovelSharpPreset(modelKey) else setAwayNovelSharpPreset(modelKey)
    }

    fun isHomeModeEnabled(): Boolean = prefs.getBoolean(KEY_HOME_MODE_ENABLED, true)

    fun setHomeModeEnabled(enabled: Boolean) = prefs.edit { putBoolean(KEY_HOME_MODE_ENABLED, enabled) }

    fun getHomeChatPreset(): String = getWithPrimary(KEY_CHAT)
    fun setHomeChatPreset(modelKey: String?) = put(KEY_CHAT, modelKey)
    fun getHomeThreadNamingPreset(): String = getWithPrimary(KEY_THREAD_NAMING)
    fun setHomeThreadNamingPreset(modelKey: String?) = put(KEY_THREAD_NAMING, modelKey)
    fun getHomeSearchPreset(): String = getWithPrimary(KEY_SEARCH)
    fun setHomeSearchPreset(modelKey: String?) = put(KEY_SEARCH, modelKey)
    fun getHomeSummaryPreset(): String = getWithPrimary(KEY_SUMMARY)
    fun setHomeSummaryPreset(modelKey: String?) = put(KEY_SUMMARY, modelKey)
    fun getHomeNovelSharpPreset(): String = getWithPrimary(KEY_NOVEL_SHARP)
    fun setHomeNovelSharpPreset(modelKey: String?) = put(KEY_NOVEL_SHARP, modelKey)

    fun getAwayChatPreset(): String = getWithPrimary(KEY_CHAT_AWAY)
    fun setAwayChatPreset(modelKey: String?) = put(KEY_CHAT_AWAY, modelKey)
    fun getAwayThreadNamingPreset(): String = getWithPrimary(KEY_THREAD_NAMING_AWAY)
    fun setAwayThreadNamingPreset(modelKey: String?) = put(KEY_THREAD_NAMING_AWAY, modelKey)
    fun getAwaySearchPreset(): String = getWithPrimary(KEY_SEARCH_AWAY)
    fun setAwaySearchPreset(modelKey: String?) = put(KEY_SEARCH_AWAY, modelKey)
    fun getAwaySummaryPreset(): String = getWithPrimary(KEY_SUMMARY_AWAY)
    fun setAwaySummaryPreset(modelKey: String?) = put(KEY_SUMMARY_AWAY, modelKey)
    fun getAwayNovelSharpPreset(): String = getWithPrimary(KEY_NOVEL_SHARP_AWAY)
    fun setAwayNovelSharpPreset(modelKey: String?) = put(KEY_NOVEL_SHARP_AWAY, modelKey)

    /** 主预设：作为未单独设置的任务的回退 */
    fun getPrimaryPreset(): String = prefs.getString(KEY_PRIMARY, "") ?: ""

    private fun getWithPrimary(key: String): String {
        val v = prefs.getString(key, "") ?: ""
        return if (v.isNotEmpty()) v else getPrimaryPreset()
    }

    private fun put(key: String, value: String?) {
        prefs.edit { putString(key, value ?: "") }
    }

    /**
     * 内置回退：当无预设时，取第一个已配置的模型。
     */
    fun getFirstAvailablePreset(): String {
        return try {
            val opts = ConfiguredModelPicker.getConfiguredModels(context)
            if (opts.isNullOrEmpty()) "" else opts[0].getStorageKey()
        } catch (e: Exception) {
            ""
        }
    }

    /** 迁移自 ConfigManager 的旧值（首次启动时调用） */
    fun migrateFromConfigManager() {
        try {
            if (prefs.contains(KEY_CHAT)) return
            val cm = ConfigManager(context)
            val chat = cm.getModel()
            if (chat.isNotEmpty()) setChatPreset(chat)
            val tn = cm.getThreadNamingModel()
            if (tn.isNotEmpty()) setThreadNamingPreset(tn)
            val sr = cm.getSearchModel()
            if (sr.isNotEmpty()) setSearchPreset(sr)
            val su = cm.getSummaryModel()
            if (su.isNotEmpty()) setSummaryPreset(su)
        } catch (ignored: Exception) {}
    }

    companion object {
        private const val PREFS = "aichat_model_config"
        private const val KEY_CHAT = "preset_chat"
        private const val KEY_THREAD_NAMING = "preset_thread_naming"
        private const val KEY_SEARCH = "preset_search"
        private const val KEY_SUMMARY = "preset_summary"
        private const val KEY_NOVEL_SHARP = "preset_novel_sharp"
        private const val KEY_CHAT_AWAY = "preset_chat_away"
        private const val KEY_THREAD_NAMING_AWAY = "preset_thread_naming_away"
        private const val KEY_SEARCH_AWAY = "preset_search_away"
        private const val KEY_SUMMARY_AWAY = "preset_summary_away"
        private const val KEY_NOVEL_SHARP_AWAY = "preset_novel_sharp_away"
        private const val KEY_HOME_MODE_ENABLED = "home_mode_enabled"

        /** 主预设：当某任务未单独设置时，回退到此 */
        private const val KEY_PRIMARY = "preset_primary"
    }
}
