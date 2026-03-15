package com.example.aichat;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/**
 * 模型配置：各任务选用的模型预设。
 * 模型配置页（ModelConfigActivity）修改选用值；
 * 聊天等实际调用从 AiModelConfig 读取，AiModelConfig 从此处取预设的 modelKey。
 */
public class ModelConfig {

    private static final String PREFS = "aichat_model_config";
    private static final String KEY_CHAT = "preset_chat";
    private static final String KEY_THREAD_NAMING = "preset_thread_naming";
    private static final String KEY_SEARCH = "preset_search";
    private static final String KEY_SUMMARY = "preset_summary";
    private static final String KEY_NOVEL_SHARP = "preset_novel_sharp";
    private static final String KEY_CHAT_AWAY = "preset_chat_away";
    private static final String KEY_THREAD_NAMING_AWAY = "preset_thread_naming_away";
    private static final String KEY_SEARCH_AWAY = "preset_search_away";
    private static final String KEY_SUMMARY_AWAY = "preset_summary_away";
    private static final String KEY_NOVEL_SHARP_AWAY = "preset_novel_sharp_away";
    private static final String KEY_HOME_MODE_ENABLED = "home_mode_enabled";

    /** 主预设：当某任务未单独设置时，回退到此 */
    private static final String KEY_PRIMARY = "preset_primary";

    private final SharedPreferences prefs;
    private final Context context;

    public ModelConfig(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 对话选用的预设 (providerId:modelId) */
    public String getChatPreset() {
        return isHomeModeEnabled() ? getHomeChatPreset() : getAwayChatPreset();
    }

    public void setChatPreset(String modelKey) {
        if (isHomeModeEnabled()) {
            setHomeChatPreset(modelKey);
        } else {
            setAwayChatPreset(modelKey);
        }
        if (modelKey != null && !modelKey.isEmpty()) {
            put(KEY_PRIMARY, modelKey);
        }
    }

    /** 话题命名选用的预设 */
    public String getThreadNamingPreset() {
        return isHomeModeEnabled() ? getHomeThreadNamingPreset() : getAwayThreadNamingPreset();
    }

    public void setThreadNamingPreset(String modelKey) {
        if (isHomeModeEnabled()) setHomeThreadNamingPreset(modelKey);
        else setAwayThreadNamingPreset(modelKey);
    }

    /** 搜索选用的预设 */
    public String getSearchPreset() {
        return isHomeModeEnabled() ? getHomeSearchPreset() : getAwaySearchPreset();
    }

    public void setSearchPreset(String modelKey) {
        if (isHomeModeEnabled()) setHomeSearchPreset(modelKey);
        else setAwaySearchPreset(modelKey);
    }

    /** 总结选用的预设 */
    public String getSummaryPreset() {
        return isHomeModeEnabled() ? getHomeSummaryPreset() : getAwaySummaryPreset();
    }

    public void setSummaryPreset(String modelKey) {
        if (isHomeModeEnabled()) setHomeSummaryPreset(modelKey);
        else setAwaySummaryPreset(modelKey);
    }

    /** 小说敏锐选用的预设 */
    public String getNovelSharpPreset() {
        return isHomeModeEnabled() ? getHomeNovelSharpPreset() : getAwayNovelSharpPreset();
    }

    public void setNovelSharpPreset(String modelKey) {
        if (isHomeModeEnabled()) setHomeNovelSharpPreset(modelKey);
        else setAwayNovelSharpPreset(modelKey);
    }

    public boolean isHomeModeEnabled() {
        return prefs.getBoolean(KEY_HOME_MODE_ENABLED, true);
    }

    public void setHomeModeEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_HOME_MODE_ENABLED, enabled).apply();
    }

    public String getHomeChatPreset() { return getWithPrimary(KEY_CHAT); }
    public void setHomeChatPreset(String modelKey) { put(KEY_CHAT, modelKey); }
    public String getHomeThreadNamingPreset() { return getWithPrimary(KEY_THREAD_NAMING); }
    public void setHomeThreadNamingPreset(String modelKey) { put(KEY_THREAD_NAMING, modelKey); }
    public String getHomeSearchPreset() { return getWithPrimary(KEY_SEARCH); }
    public void setHomeSearchPreset(String modelKey) { put(KEY_SEARCH, modelKey); }
    public String getHomeSummaryPreset() { return getWithPrimary(KEY_SUMMARY); }
    public void setHomeSummaryPreset(String modelKey) { put(KEY_SUMMARY, modelKey); }
    public String getHomeNovelSharpPreset() { return getWithPrimary(KEY_NOVEL_SHARP); }
    public void setHomeNovelSharpPreset(String modelKey) { put(KEY_NOVEL_SHARP, modelKey); }

    public String getAwayChatPreset() { return getWithPrimary(KEY_CHAT_AWAY); }
    public void setAwayChatPreset(String modelKey) { put(KEY_CHAT_AWAY, modelKey); }
    public String getAwayThreadNamingPreset() { return getWithPrimary(KEY_THREAD_NAMING_AWAY); }
    public void setAwayThreadNamingPreset(String modelKey) { put(KEY_THREAD_NAMING_AWAY, modelKey); }
    public String getAwaySearchPreset() { return getWithPrimary(KEY_SEARCH_AWAY); }
    public void setAwaySearchPreset(String modelKey) { put(KEY_SEARCH_AWAY, modelKey); }
    public String getAwaySummaryPreset() { return getWithPrimary(KEY_SUMMARY_AWAY); }
    public void setAwaySummaryPreset(String modelKey) { put(KEY_SUMMARY_AWAY, modelKey); }
    public String getAwayNovelSharpPreset() { return getWithPrimary(KEY_NOVEL_SHARP_AWAY); }
    public void setAwayNovelSharpPreset(String modelKey) { put(KEY_NOVEL_SHARP_AWAY, modelKey); }

    /** 主预设：作为未单独设置的任务的回退 */
    public String getPrimaryPreset() {
        return prefs.getString(KEY_PRIMARY, "");
    }

    private String getWithPrimary(String key) {
        String v = prefs.getString(key, "");
        if (v != null && !v.isEmpty()) return v;
        return getPrimaryPreset();
    }

    private void put(String key, String value) {
        prefs.edit().putString(key, value != null ? value : "").apply();
    }

    /**
     * 内置回退：当无预设时，取第一个已配置的模型。
     */
    public String getFirstAvailablePreset() {
        try {
            List<ConfiguredModelPicker.Option> opts = ConfiguredModelPicker.getConfiguredModels(context);
            return (opts == null || opts.isEmpty()) ? "" : opts.get(0).getStorageKey();
        } catch (Exception e) {
            return "";
        }
    }

    /** 迁移自 ConfigManager 的旧值（首次启动时调用） */
    public void migrateFromConfigManager() {
        try {
            if (prefs.contains(KEY_CHAT)) return;
            ConfigManager cm = new ConfigManager(context);
            String chat = cm.getModel();
            if (chat != null && !chat.isEmpty()) setChatPreset(chat);
            String tn = cm.getThreadNamingModel();
            if (tn != null && !tn.isEmpty()) setThreadNamingPreset(tn);
            String sr = cm.getSearchModel();
            if (sr != null && !sr.isEmpty()) setSearchPreset(sr);
            String su = cm.getSummaryModel();
            if (su != null && !su.isEmpty()) setSummaryPreset(su);
        } catch (Exception ignored) {}
    }
}
