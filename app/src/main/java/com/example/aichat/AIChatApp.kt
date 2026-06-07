package com.example.aichat

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.example.aichat.story.InkosRulesMigration
import com.example.aichat.sync.RemoteSyncConfigStore
import com.example.aichat.sync.SyncScheduler
import com.example.aichat.sync.WsClient
import com.mikepenz.iconics.Iconics
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class AIChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Iconics.init(this) // typefaces auto-register via ContentProvider
        PDFBoxResourceLoader.init(this)
        applyTheme()
        VolcEngineTTSManager.init(this)
        VolcEngineHttpTTS.init(cacheDir)
        RoomMigrationHelper.migrateIfNeeded(this)
        // v16 后置迁移: 把 Room 暂存的旧 inkosBookRulesYaml 写成 rules outline item.
        InkosRulesMigration.runIfPending(this)
        ProactiveMessageNotifier(this).ensureChannel()
        if (RemoteSyncConfigStore(this).isEnabled()) {
            SyncScheduler.start(this)
            WsClient.start(this)
        }
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
}
