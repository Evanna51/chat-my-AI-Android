package com.example.aichat

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.Executors

/**
 * 一次性迁移器：将 SharedPreferences 中的旧数据迁移到 Room 数据库。
 * 通过标志位保证只执行一次。
 */
object RoomMigrationHelper {
    private const val TAG = "RoomMigrationHelper"
    private const val PREFS_MIGRATION = "room_migration_flags"
    private const val KEY_V2_DONE = "room_migration_v2_done"
    private val GSON = Gson()

    @JvmStatic
    fun migrateIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val migrationPrefs = appContext.getSharedPreferences(PREFS_MIGRATION, Context.MODE_PRIVATE)
        if (migrationPrefs.getBoolean(KEY_V2_DONE, false)) {
            return // 已迁移，跳过
        }
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val db = AppDatabase.getInstance(appContext)
                migrateSessionMeta(appContext, db)
                migrateSessionChatOptions(appContext, db)
                migrateMyAssistants(appContext, db)
                migrateSessionAssistantBindings(appContext, db)
                migrationPrefs.edit().putBoolean(KEY_V2_DONE, true).apply()
                Log.i(TAG, "Room migration v2 completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Room migration v2 failed", e)
                // 不设置标志位，下次启动继续尝试
            } finally {
                executor.shutdown()
            }
        }
    }

    private fun migrateSessionMeta(context: Context, db: AppDatabase) {
        val prefs: SharedPreferences = context.getSharedPreferences("aichat_session_meta", Context.MODE_PRIVATE)
        val json = prefs.getString("session_meta_map", "{}")
        val type = object : TypeToken<Map<String, SessionMeta>>() {}.type
        val map: Map<String, SessionMeta>? = GSON.fromJson(json, type)
        if (map == null) return
        val dao = db.sessionMetaDao()
        for ((sessionId, meta) in map) {
            if (sessionId.isEmpty() || meta == null) continue
            val entity = SessionMetaEntity()
            entity.sessionId = sessionId
            entity.title = meta.title ?: ""
            entity.outline = meta.outline ?: ""
            entity.avatar = meta.avatar ?: ""
            entity.category = if (!meta.category?.trim().isNullOrEmpty()) meta.category!!.trim() else "默认"
            entity.favorite = meta.favorite
            entity.pinned = meta.pinned
            entity.hidden = meta.hidden
            entity.deleted = meta.deleted
            dao.upsert(entity)
        }
        Log.d(TAG, "Migrated " + map.size + " session_meta entries")
    }

    private fun migrateSessionChatOptions(context: Context, db: AppDatabase) {
        val prefs: SharedPreferences = context.getSharedPreferences("aichat_session_chat_options", Context.MODE_PRIVATE)
        val json = prefs.getString("session_options_map", "{}")
        val type = object : TypeToken<Map<String, SessionChatOptions>>() {}.type
        val map: Map<String, SessionChatOptions>? = GSON.fromJson(json, type)
        if (map == null) return
        val dao = db.sessionChatOptionsDao()
        for ((sessionId, opts) in map) {
            if (sessionId.isEmpty() || opts == null) continue
            val entity = SessionChatOptionsEntity()
            entity.sessionId = sessionId
            entity.sessionTitle = opts.sessionTitle ?: ""
            entity.sessionAvatar = opts.sessionAvatar ?: ""
            entity.modelKey = opts.modelKey ?: ""
            entity.systemPrompt = opts.systemPrompt ?: ""
            entity.stop = opts.stop ?: ""
            entity.contextMessageCount = opts.contextMessageCount
            entity.googleThinkingBudget = opts.googleThinkingBudget
            entity.temperature = opts.temperature
            entity.topP = opts.topP
            entity.streamOutput = true // always force true per existing contract
            entity.autoChapterPlan = opts.autoChapterPlan
            entity.thinking = opts.thinking
            dao.upsert(entity)
        }
        Log.d(TAG, "Migrated " + map.size + " session_chat_options entries")
    }

    private fun migrateMyAssistants(context: Context, db: AppDatabase) {
        val prefs: SharedPreferences = context.getSharedPreferences("aichat_my_assistants", Context.MODE_PRIVATE)
        val json = prefs.getString("assistant_list", "[]")
        val type = object : TypeToken<List<MyAssistant>>() {}.type
        var list: List<MyAssistant>? = GSON.fromJson(json, type)
        if (list == null) list = ArrayList()
        val dao = db.myAssistantDao()
        for (a in list) {
            if (a == null || a.id.isNullOrEmpty()) continue
            // Apply same prompt→options.systemPrompt migration logic as MyAssistantStore.getAll()
            if (a.options == null) a.options = SessionChatOptions()
            val opts = a.options!!
            val optionPrompt = opts.systemPrompt?.trim() ?: ""
            val legacyPrompt = a.prompt?.trim() ?: ""
            if (optionPrompt.isEmpty() && legacyPrompt.isNotEmpty()) {
                opts.systemPrompt = legacyPrompt
            }
            val entity = MyAssistantEntity()
            entity.id = a.id
            entity.name = a.name
            entity.prompt = "" // cleared after migration
            entity.avatar = a.avatar
            entity.avatarImageBase64 = a.avatarImageBase64
            entity.firstDialogue = a.firstDialogue
            entity.type = a.type
            entity.allowAutoLife = a.allowAutoLife
            entity.allowProactiveMessage = a.allowProactiveMessage
            entity.optionsJson = GSON.toJson(a.options)
            entity.updatedAt = a.updatedAt
            dao.upsert(entity)
        }
        Log.d(TAG, "Migrated " + list.size + " my_assistant entries")
    }

    private fun migrateSessionAssistantBindings(context: Context, db: AppDatabase) {
        val prefs: SharedPreferences = context.getSharedPreferences("aichat_session_assistant_bindings", Context.MODE_PRIVATE)
        val json = prefs.getString("session_assistant_map", "{}")
        val type = object : TypeToken<Map<String, String>>() {}.type
        var map: Map<String, String>? = GSON.fromJson(json, type)
        if (map == null) map = HashMap()
        val dao = db.sessionAssistantBindingDao()
        for ((sessionId, assistantId) in map) {
            if (sessionId.isEmpty()) continue
            if (assistantId.isEmpty()) continue
            val entity = SessionAssistantBindingEntity()
            entity.sessionId = sessionId
            entity.assistantId = assistantId
            dao.upsert(entity)
        }
        Log.d(TAG, "Migrated " + map.size + " session_assistant_binding entries")
    }
}
