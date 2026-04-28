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

    enum class Scene(val key: String) {
        HOME("home"), AWAY("away"), CUSTOM("custom");
        companion object {
            fun fromKey(key: String?): Scene = when (key) {
                AWAY.key -> AWAY
                CUSTOM.key -> CUSTOM
                else -> HOME
            }
        }
    }

    /** 当前生效的场景 */
    fun getActiveScene(): Scene {
        val raw = prefs.getString(KEY_ACTIVE_SCENE, null)
        if (!raw.isNullOrEmpty()) return Scene.fromKey(raw)
        // Legacy migration: home_mode_enabled boolean → home/away
        if (prefs.contains(KEY_HOME_MODE_ENABLED)) {
            return if (prefs.getBoolean(KEY_HOME_MODE_ENABLED, true)) Scene.HOME else Scene.AWAY
        }
        return Scene.HOME
    }

    fun setActiveScene(scene: Scene) = prefs.edit {
        putString(KEY_ACTIVE_SCENE, scene.key)
        // Keep legacy boolean roughly in sync so any code still reading it
        // (and external SP dumps) doesn't drift wildly. CUSTOM maps to false.
        putBoolean(KEY_HOME_MODE_ENABLED, scene == Scene.HOME)
    }

    /** 对话选用的预设 (providerId:modelId) */
    fun getChatPreset(): String = getPreset(getActiveScene(), Field.CHAT)
    fun setChatPreset(modelKey: String?) {
        setPreset(getActiveScene(), Field.CHAT, modelKey)
        if (!modelKey.isNullOrEmpty()) put(KEY_PRIMARY, modelKey)
    }

    /** 话题命名选用的预设 */
    fun getThreadNamingPreset(): String = getPreset(getActiveScene(), Field.THREAD_NAMING)
    fun setThreadNamingPreset(modelKey: String?) =
        setPreset(getActiveScene(), Field.THREAD_NAMING, modelKey)

    /** 搜索选用的预设 */
    fun getSearchPreset(): String = getPreset(getActiveScene(), Field.SEARCH)
    fun setSearchPreset(modelKey: String?) =
        setPreset(getActiveScene(), Field.SEARCH, modelKey)

    /** 嵌入选用的预设 */
    fun getEmbeddingPreset(): String = getPreset(getActiveScene(), Field.EMBEDDING)
    fun setEmbeddingPreset(modelKey: String?) =
        setPreset(getActiveScene(), Field.EMBEDDING, modelKey)

    /** 总结选用的预设 */
    fun getSummaryPreset(): String = getPreset(getActiveScene(), Field.SUMMARY)
    fun setSummaryPreset(modelKey: String?) =
        setPreset(getActiveScene(), Field.SUMMARY, modelKey)

    /** 小说敏锐选用的预设：当前 scene 没设 → 其他 scene → primary */
    fun getNovelSharpPreset(): String {
        val active = getActiveScene()
        val current = prefs.getString(prefKey(active, Field.NOVEL_SHARP), "") ?: ""
        if (current.isNotEmpty()) return current
        for (s in Scene.values()) {
            if (s == active) continue
            val v = prefs.getString(prefKey(s, Field.NOVEL_SHARP), "") ?: ""
            if (v.isNotEmpty()) return v
        }
        return getPrimaryPreset()
    }

    fun setNovelSharpPreset(modelKey: String?) =
        setPreset(getActiveScene(), Field.NOVEL_SHARP, modelKey)

    /** Scene-scoped accessor. */
    fun getPreset(scene: Scene, field: Field): String =
        getWithPrimary(prefKey(scene, field))

    fun setPreset(scene: Scene, field: Field, modelKey: String?) =
        put(prefKey(scene, field), modelKey)

    @Deprecated("Use getActiveScene() == Scene.HOME", ReplaceWith("getActiveScene() == ModelConfig.Scene.HOME"))
    fun isHomeModeEnabled(): Boolean = getActiveScene() == Scene.HOME

    @Deprecated("Use setActiveScene(...)", ReplaceWith("setActiveScene(if (enabled) ModelConfig.Scene.HOME else ModelConfig.Scene.AWAY)"))
    fun setHomeModeEnabled(enabled: Boolean) =
        setActiveScene(if (enabled) Scene.HOME else Scene.AWAY)

    enum class Field(val keyChat: String, val keyAway: String, val keyCustom: String) {
        CHAT(KEY_CHAT, KEY_CHAT_AWAY, KEY_CHAT_CUSTOM),
        THREAD_NAMING(KEY_THREAD_NAMING, KEY_THREAD_NAMING_AWAY, KEY_THREAD_NAMING_CUSTOM),
        SEARCH(KEY_SEARCH, KEY_SEARCH_AWAY, KEY_SEARCH_CUSTOM),
        SUMMARY(KEY_SUMMARY, KEY_SUMMARY_AWAY, KEY_SUMMARY_CUSTOM),
        NOVEL_SHARP(KEY_NOVEL_SHARP, KEY_NOVEL_SHARP_AWAY, KEY_NOVEL_SHARP_CUSTOM),
        EMBEDDING(KEY_EMBEDDING, KEY_EMBEDDING_AWAY, KEY_EMBEDDING_CUSTOM);
    }

    private fun prefKey(scene: Scene, field: Field): String = when (scene) {
        Scene.HOME -> field.keyChat
        Scene.AWAY -> field.keyAway
        Scene.CUSTOM -> field.keyCustom
    }

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
    fun getHomeEmbeddingPreset(): String = getWithPrimary(KEY_EMBEDDING)
    fun setHomeEmbeddingPreset(modelKey: String?) = put(KEY_EMBEDDING, modelKey)

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
    fun getAwayEmbeddingPreset(): String = getWithPrimary(KEY_EMBEDDING_AWAY)
    fun setAwayEmbeddingPreset(modelKey: String?) = put(KEY_EMBEDDING_AWAY, modelKey)

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
        const val KEY_CHAT = "preset_chat"
        const val KEY_THREAD_NAMING = "preset_thread_naming"
        const val KEY_SEARCH = "preset_search"
        const val KEY_SUMMARY = "preset_summary"
        const val KEY_NOVEL_SHARP = "preset_novel_sharp"
        const val KEY_EMBEDDING = "preset_embedding"
        const val KEY_CHAT_AWAY = "preset_chat_away"
        const val KEY_THREAD_NAMING_AWAY = "preset_thread_naming_away"
        const val KEY_SEARCH_AWAY = "preset_search_away"
        const val KEY_SUMMARY_AWAY = "preset_summary_away"
        const val KEY_NOVEL_SHARP_AWAY = "preset_novel_sharp_away"
        const val KEY_EMBEDDING_AWAY = "preset_embedding_away"
        const val KEY_CHAT_CUSTOM = "preset_chat_custom"
        const val KEY_THREAD_NAMING_CUSTOM = "preset_thread_naming_custom"
        const val KEY_SEARCH_CUSTOM = "preset_search_custom"
        const val KEY_SUMMARY_CUSTOM = "preset_summary_custom"
        const val KEY_NOVEL_SHARP_CUSTOM = "preset_novel_sharp_custom"
        const val KEY_EMBEDDING_CUSTOM = "preset_embedding_custom"
        const val KEY_HOME_MODE_ENABLED = "home_mode_enabled"
        const val KEY_ACTIVE_SCENE = "active_scene"

        /** 主预设：当某任务未单独设置时，回退到此 */
        private const val KEY_PRIMARY = "preset_primary"
    }
}
