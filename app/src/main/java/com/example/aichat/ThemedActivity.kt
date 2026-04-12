package com.example.aichat

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

abstract class ThemedActivity : AppCompatActivity() {
    private var appliedThemeResId: Int = 0

    override fun attachBaseContext(newBase: Context?) {
        val wrapped = newBase?.let { ThemeSettingsHelper.wrapForFontScale(it) }
        super.attachBaseContext(wrapped ?: newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedThemeResId = ThemeSettingsHelper.resolveThemeResId(this)
        setTheme(appliedThemeResId)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        val currentThemeResId = ThemeSettingsHelper.resolveThemeResId(this)
        if (currentThemeResId != appliedThemeResId) {
            recreate()
        }
    }
}
