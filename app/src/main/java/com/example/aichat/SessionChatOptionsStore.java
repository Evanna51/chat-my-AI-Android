package com.example.aichat;

import android.content.Context;

public class SessionChatOptionsStore {

    private final SessionChatOptionsDao dao;

    public SessionChatOptionsStore(Context context) {
        dao = AppDatabase.getInstance(context).sessionChatOptionsDao();
    }

    public SessionChatOptions get(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return defaultOptions();
        SessionChatOptionsEntity entity = dao.get(sessionId);
        SessionChatOptions out = entity != null ? fromEntity(entity) : new SessionChatOptions();
        out.streamOutput = true; // always force true per existing contract
        return out;
    }

    public boolean has(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return false;
        return dao.has(sessionId) > 0;
    }

    public void save(String sessionId, SessionChatOptions options) {
        if (sessionId == null || sessionId.isEmpty() || options == null) return;
        options.streamOutput = true;
        dao.upsert(toEntity(sessionId, options));
    }

    public void remove(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        dao.delete(sessionId);
    }

    // --- Conversion helpers ---

    private static SessionChatOptions defaultOptions() {
        SessionChatOptions opts = new SessionChatOptions();
        opts.streamOutput = true;
        return opts;
    }

    private static SessionChatOptionsEntity toEntity(String sessionId, SessionChatOptions opts) {
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
        entity.streamOutput = true;
        entity.autoChapterPlan = opts.autoChapterPlan;
        entity.thinking = opts.thinking;
        return entity;
    }

    private static SessionChatOptions fromEntity(SessionChatOptionsEntity entity) {
        SessionChatOptions opts = new SessionChatOptions();
        opts.sessionTitle = entity.sessionTitle != null ? entity.sessionTitle : "";
        opts.sessionAvatar = entity.sessionAvatar != null ? entity.sessionAvatar : "";
        opts.modelKey = entity.modelKey != null ? entity.modelKey : "";
        opts.systemPrompt = entity.systemPrompt != null ? entity.systemPrompt : "";
        opts.stop = entity.stop != null ? entity.stop : "";
        opts.contextMessageCount = entity.contextMessageCount;
        opts.googleThinkingBudget = entity.googleThinkingBudget;
        opts.temperature = entity.temperature;
        opts.topP = entity.topP;
        opts.streamOutput = true;
        opts.autoChapterPlan = entity.autoChapterPlan;
        opts.thinking = entity.thinking;
        return opts;
    }
}
