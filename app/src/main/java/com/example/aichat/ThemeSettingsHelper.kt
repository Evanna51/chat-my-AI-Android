package com.example.aichat

import android.content.Context
import android.content.res.Configuration

object ThemeSettingsHelper {

    @JvmStatic
    fun resolveThemeResId(context: Context): Int {
        val color = ConfigManager(context).getThemeColor()
        return when (color) {
            "green" -> R.style.Theme_AIChat_Green
            "purple" -> R.style.Theme_AIChat_Purple
            "orange" -> R.style.Theme_AIChat_Orange
            else -> R.style.Theme_AIChat_Blue
        }
    }

    @JvmStatic
    fun wrapForFontScale(base: Context?): Context? {
        if (base == null) return null
        val scale = resolveFontScale(base)
        val configuration = Configuration(base.resources.configuration)
        if (Math.abs(configuration.fontScale - scale) < 0.0001f) return base
        configuration.fontScale = scale
        return base.createConfigurationContext(configuration)
    }

    private fun resolveFontScale(context: Context): Float {
        val size = ConfigManager(context).getFontSize()
        val scale = size / 14.0f
        if (scale < 0.85f) return 0.85f
        if (scale > 1.45f) return 1.45f
        return scale
    }
}
