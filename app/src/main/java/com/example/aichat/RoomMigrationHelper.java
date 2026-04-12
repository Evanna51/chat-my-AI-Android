package com.example.aichat;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 一次性迁移器：将 SharedPreferences 中的旧数据迁移到 Room 数据库。
 * 通过标志位保证只执行一次。
 */
public class RoomMigrationHelper {
    private static final String TAG = "RoomMigrationHelper";
    private static final String PREFS_MIGRATION = "room_migration_flags";
    private static final String KEY_V2_DONE = "room_migration_v2_done";
    private static final Gson GSON = new Gson();

    public static void migrateIfNeeded(Context context) {
        Context appContext = context.getApplicationContext();
        SharedPreferences migrationPrefs = appContext.getSharedPreferences(PREFS_MIGRATION, Context.MODE_PRIVATE);
        if (migrationPrefs.getBoolean(KEY_V2_DONE, false)) {
            return; // 已迁移，跳过
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(appContext);
                migrateSessionMeta(appContext, db);
                migrateSessionChatOptions(appContext, db);
                migrateMyAssistants(appContext, db);
                migrateSessionAssistantBindings(appContext, db);
                migrationPrefs.edit().putBoolean(KEY_V2_DONE, true).apply();
                Log.i(TAG, "Room migration v2 completed successfully");
            } catch (Exception e) {
                Log.e(TAG, "Room migration v2 failed", e);
                // 不设置标志位，下次启动继续尝试
            } finally {
                executor.shutdown();
            }
        });
    }

    private static void migrateSessionMeta(Context context, AppDatabase db) {
        SharedPreferences prefs = context.getSharedPreferences("aichat_session_meta", Context.MODE_PRIVATE);
        String json = prefs.getString("session_meta_map", "{}");
        Type type = new TypeToken<Map<String, SessionMeta>>() {}.getType();
        Map<String, SessionMeta> map = GSON.fromJson(json, type);
        if (map == null) return;
        SessionMetaDao dao = db.sessionMetaDao();
        for (Map.Entry<String, SessionMeta> entry : map.entrySet()) {
            String sessionId = entry.getKey();
            SessionMeta meta = entry.getValue();
            if (sessionId == null || sessionId.isEmpty() || meta == null) continue;
            SessionMetaEntity entity = new SessionMetaEntity();
            entity.sessionId = sessionId;
            entity.title = meta.title != null ? meta.title : "";
            entity.outline = meta.outline != null ? meta.outline : "";
            entity.avatar = meta.avatar != null ? meta.avatar : "";
            entity.category = (meta.category != null && !meta.category.trim().isEmpty())
                    ? meta.category.trim() : "默认";
            entity.favorite = meta.favorite;
            entity.pinned = meta.pinned;
            entity.hidden = meta.hidden;
            entity.deleted = meta.deleted;
            dao.upsert(entity);
        }
        Log.d(TAG, "Migrated " + map.size() + " session_meta entries");
    }

    private static void migrateSessionChatOptions(Context context, AppDatabase db) {
        SharedPreferences prefs = context.getSharedPreferences("aichat_session_chat_options", Context.MODE_PRIVATE);
        String json = prefs.getString("session_options_map", "{}");
        Type type = new TypeToken<Map<String, SessionChatOptions>>() {}.getType();
        Map<String, SessionChatOptions> map = GSON.fromJson(json, type);
        if (map == null) return;
        SessionChatOptionsDao dao = db.sessionChatOptionsDao();
        for (Map.Entry<String, SessionChatOptions> entry : map.entrySet()) {
            String sessionId = entry.getKey();
            SessionChatOptions opts = entry.getValue();
            if (sessionId == null || sessionId.isEmpty() || opts == null) continue;
            SessionChatOptionsEntity entity = new SessionChatOptionsEntity();
            entity.sessionId = sessionId;
            entity.sessionTitle = opts.sessionTitle != null ? opts.sessionTitle : "";
            entity.sessionAvatar = opts.sessionAvatar != null ? opts.sessionAvatar : "";
            entity.modelKey = opts.modelKey != null ? opts.modelKey : "";
            entity.systemPrompt = opts.systemPrompt != null ? opts.systemPrompt : "";
            entity.stop = opts.stop != null ? opts.stop : "";
            entity.contextMessageCount = opts.contextMessageCount;
            entity.googleThinkingBudget = opts.googleThinkingBudget;
            entity.temperature = opts.temperature;
            entity.topP = opts.topP;
            entity.streamOutput = true; // always force true per existing contract
            entity.autoChapterPlan = opts.autoChapterPlan;
            entity.thinking = opts.thinking;
            dao.upsert(entity);
        }
        Log.d(TAG, "Migrated " + map.size() + " session_chat_options entries");
    }

    private static void migrateMyAssistants(Context context, AppDatabase db) {
        SharedPreferences prefs = context.getSharedPreferences("aichat_my_assistants", Context.MODE_PRIVATE);
        String json = prefs.getString("assistant_list", "[]");
        Type type = new TypeToken<List<MyAssistant>>() {}.getType();
        List<MyAssistant> list = GSON.fromJson(json, type);
        if (list == null) list = new ArrayList<>();
        MyAssistantDao dao = db.myAssistantDao();
        for (MyAssistant a : list) {
            if (a == null || a.id == null || a.id.isEmpty()) continue;
            // Apply same prompt→options.systemPrompt migration logic as MyAssistantStore.getAll()
            if (a.options == null) a.options = new SessionChatOptions();
            String optionPrompt = a.options.systemPrompt != null ? a.options.systemPrompt.trim() : "";
            String legacyPrompt = a.prompt != null ? a.prompt.trim() : "";
            if (optionPrompt.isEmpty() && !legacyPrompt.isEmpty()) {
                a.options.systemPrompt = legacyPrompt;
            }
            MyAssistantEntity entity = new MyAssistantEntity();
            entity.id = a.id;
            entity.name = a.name;
            entity.prompt = ""; // cleared after migration
            entity.avatar = a.avatar;
            entity.avatarImageBase64 = a.avatarImageBase64;
            entity.firstDialogue = a.firstDialogue;
            entity.type = a.type;
            entity.allowAutoLife = a.allowAutoLife;
            entity.allowProactiveMessage = a.allowProactiveMessage;
            entity.optionsJson = GSON.toJson(a.options);
            entity.updatedAt = a.updatedAt;
            dao.upsert(entity);
        }
        Log.d(TAG, "Migrated " + list.size() + " my_assistant entries");
    }

    private static void migrateSessionAssistantBindings(Context context, AppDatabase db) {
        SharedPreferences prefs = context.getSharedPreferences("aichat_session_assistant_bindings", Context.MODE_PRIVATE);
        String json = prefs.getString("session_assistant_map", "{}");
        Type type = new TypeToken<Map<String, String>>() {}.getType();
        Map<String, String> map = GSON.fromJson(json, type);
        if (map == null) map = new HashMap<>();
        SessionAssistantBindingDao dao = db.sessionAssistantBindingDao();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String sessionId = entry.getKey();
            String assistantId = entry.getValue();
            if (sessionId == null || sessionId.isEmpty()) continue;
            if (assistantId == null || assistantId.isEmpty()) continue;
            SessionAssistantBindingEntity entity = new SessionAssistantBindingEntity();
            entity.sessionId = sessionId;
            entity.assistantId = assistantId;
            dao.upsert(entity);
        }
        Log.d(TAG, "Migrated " + map.size() + " session_assistant_binding entries");
    }
}
