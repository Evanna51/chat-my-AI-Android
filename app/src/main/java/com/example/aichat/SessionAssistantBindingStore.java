package com.example.aichat;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

public class SessionAssistantBindingStore {

    private final SessionAssistantBindingDao dao;

    public SessionAssistantBindingStore(Context context) {
        dao = AppDatabase.getInstance(context).sessionAssistantBindingDao();
    }

    public void bind(String sessionId, String assistantId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        if (assistantId == null || assistantId.isEmpty()) {
            dao.delete(sessionId);
            return;
        }
        SessionAssistantBindingEntity entity = new SessionAssistantBindingEntity();
        entity.sessionId = sessionId;
        entity.assistantId = assistantId;
        dao.upsert(entity);
    }

    public String getAssistantId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return "";
        String result = dao.getAssistantId(sessionId);
        return result != null ? result : "";
    }

    public void remove(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        dao.delete(sessionId);
    }

    public boolean containsSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) return false;
        return dao.contains(sessionId.trim()) > 0;
    }

    public List<String> getSessionIdsByAssistantId(String assistantId) {
        String target = assistantId != null ? assistantId.trim() : "";
        if (target.isEmpty()) return new ArrayList<>();
        List<String> result = dao.getSessionIdsByAssistantId(target);
        return result != null ? result : new ArrayList<>();
    }
}
