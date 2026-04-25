package com.example.aichat

import android.content.Context

/**
 * 角色助手「自动朗读」开关的持久化。
 * 状态按助手 id（assistantId）维度保存——同一个角色再次开会话时记住开关状态。
 */
class AutoReadStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(assistantId: String?): Boolean {
        if (assistantId.isNullOrEmpty()) return false
        return prefs.getBoolean(KEY_PREFIX + assistantId, false)
    }

    fun setEnabled(assistantId: String?, enabled: Boolean) {
        if (assistantId.isNullOrEmpty()) return
        prefs.edit().putBoolean(KEY_PREFIX + assistantId, enabled).apply()
    }

    companion object {
        private const val PREFS = "aichat_auto_read"
        private const val KEY_PREFIX = "auto_read_"
    }
}
