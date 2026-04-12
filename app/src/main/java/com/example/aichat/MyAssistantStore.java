package com.example.aichat;

import android.content.Context;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MyAssistantStore {
    private static final Gson GSON = new Gson();

    private final MyAssistantDao dao;

    public MyAssistantStore(Context context) {
        dao = AppDatabase.getInstance(context).myAssistantDao();
    }

    public List<MyAssistant> getAll() {
        List<MyAssistantEntity> entities = dao.getAll();
        List<MyAssistant> result = new ArrayList<>();
        if (entities != null) {
            for (MyAssistantEntity entity : entities) {
                if (entity != null) result.add(fromEntity(entity));
            }
        }
        return result;
    }

    public List<MyAssistant> getRecent(int n) {
        List<MyAssistant> all = getAll();
        if (all.size() <= n) return all;
        return new ArrayList<>(all.subList(0, n));
    }

    public MyAssistant getById(String id) {
        if (id == null || id.isEmpty()) return null;
        MyAssistantEntity entity = dao.getById(id);
        return entity != null ? fromEntity(entity) : null;
    }

    public MyAssistant createEmpty() {
        MyAssistant a = new MyAssistant();
        a.id = UUID.randomUUID().toString();
        a.name = "新助手";
        a.prompt = "";
        a.avatar = "";
        a.avatarImageBase64 = "";
        a.firstDialogue = "";
        a.type = "default";
        a.allowAutoLife = false;
        a.allowProactiveMessage = false;
        a.options = new SessionChatOptions();
        a.updatedAt = System.currentTimeMillis();
        return a;
    }

    public void save(MyAssistant assistant) {
        if (assistant == null || assistant.id == null || assistant.id.isEmpty()) return;
        assistant.updatedAt = System.currentTimeMillis();
        dao.upsert(toEntity(assistant));
    }

    public void delete(String id) {
        if (id == null || id.isEmpty()) return;
        dao.delete(id);
    }

    // --- Conversion helpers ---

    private static MyAssistantEntity toEntity(MyAssistant a) {
        MyAssistantEntity entity = new MyAssistantEntity();
        entity.id = a.id;
        entity.name = a.name;
        entity.prompt = ""; // always clear legacy field on write
        entity.avatar = a.avatar;
        entity.avatarImageBase64 = a.avatarImageBase64;
        entity.firstDialogue = a.firstDialogue;
        entity.type = a.type;
        entity.allowAutoLife = a.allowAutoLife;
        entity.allowProactiveMessage = a.allowProactiveMessage;
        entity.optionsJson = GSON.toJson(a.options != null ? a.options : new SessionChatOptions());
        entity.updatedAt = a.updatedAt;
        return entity;
    }

    private static MyAssistant fromEntity(MyAssistantEntity entity) {
        MyAssistant a = new MyAssistant();
        a.id = entity.id;
        a.name = entity.name;
        a.prompt = ""; // legacy field; always empty after migration
        a.avatar = entity.avatar;
        a.avatarImageBase64 = entity.avatarImageBase64;
        a.firstDialogue = entity.firstDialogue;
        a.type = entity.type;
        a.allowAutoLife = entity.allowAutoLife;
        a.allowProactiveMessage = entity.allowProactiveMessage;
        a.updatedAt = entity.updatedAt;
        // Deserialize options from JSON
        if (entity.optionsJson != null && !entity.optionsJson.isEmpty()) {
            try {
                a.options = GSON.fromJson(entity.optionsJson, SessionChatOptions.class);
            } catch (Exception ignored) {}
        }
        if (a.options == null) a.options = new SessionChatOptions();
        return a;
    }
}
