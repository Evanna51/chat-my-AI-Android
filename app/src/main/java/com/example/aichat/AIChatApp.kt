package com.example.aichat

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.mikepenz.iconics.Iconics

class AIChatApp : Application(), DefaultLifecycleObserver {
    override fun onCreate() {
        super<Application>.onCreate()
        Iconics.init(this) // typefaces auto-register via ContentProvider
        applyTheme()
        RoomMigrationHelper.migrateIfNeeded(this)
        ProactiveMessageNotifier(this).ensureChannel()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        ProactiveMessageWorkScheduler.scheduleNext(this)
    }

    private fun applyTheme() {
        val theme = ConfigManager(this).getTheme()
        val mode = when (theme) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    override fun onStart(owner: LifecycleOwner) {
        ProactiveMessageWorkScheduler.cancel(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        ProactiveMessageWorkScheduler.scheduleNext(this)
    }
}
