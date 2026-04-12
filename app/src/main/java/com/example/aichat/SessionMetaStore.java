package com.example.aichat;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class SessionMetaStore {

    private final SessionMetaDao dao;

    public SessionMetaStore(Context context) {
        dao = AppDatabase.getInstance(context).sessionMetaDao();
    }

    public SessionMeta get(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return new SessionMeta();
        SessionMetaEntity entity = dao.get(sessionId);
        return entity != null ? fromEntity(entity) : new SessionMeta();
    }

    public Map<String, SessionMeta> getAll() {
        List<SessionMetaEntity> entities = dao.getAll();
        Map<String, SessionMeta> map = new java.util.HashMap<>();
        if (entities != null) {
            for (SessionMetaEntity e : entities) {
                if (e != null) map.put(e.sessionId, fromEntity(e));
            }
        }
        return map;
    }

    public void save(String sessionId, SessionMeta meta) {
        if (sessionId == null || sessionId.isEmpty() || meta == null) return;
        dao.upsert(toEntity(sessionId, meta));
    }

    public void remove(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        dao.delete(sessionId);
    }

    public void updateTitle(String sessionId, String title) {
        dao.updateTitle(sessionId, title != null ? title.trim() : "");
    }

    public void updateCategory(String sessionId, String category) {
        String safe = (category != null && !category.trim().isEmpty()) ? category.trim() : "默认";
        dao.updateCategory(sessionId, safe);
    }

    public void setFavorite(String sessionId, boolean favorite) {
        dao.setFavorite(sessionId, favorite);
        if (favorite) dao.updateCategory(sessionId, "收藏");
    }

    public void setPinned(String sessionId, boolean pinned) {
        dao.setPinned(sessionId, pinned);
    }

    public void setDeleted(String sessionId, boolean deleted) {
        dao.setDeleted(sessionId, deleted);
    }

    public void setHidden(String sessionId, boolean hidden) {
        dao.setHidden(sessionId, hidden);
    }

    public void updateOutline(String sessionId, String outline) {
        dao.updateOutline(sessionId, outline != null ? outline.trim() : "");
    }

    public List<String> getAllCategories() {
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        categories.add("默认");
        categories.add("工作");
        categories.add("生活");
        categories.add("收藏");
        categories.add("学习");
        List<String> fromDb = dao.getAllCategories();
        if (fromDb != null) categories.addAll(fromDb);
        return new ArrayList<>(categories);
    }

    // --- Conversion helpers ---

    private static SessionMetaEntity toEntity(String sessionId, SessionMeta meta) {
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
        return entity;
    }

    private static SessionMeta fromEntity(SessionMetaEntity entity) {
        SessionMeta meta = new SessionMeta();
        meta.title = entity.title != null ? entity.title : "";
        meta.outline = entity.outline != null ? entity.outline : "";
        meta.avatar = entity.avatar != null ? entity.avatar : "";
        meta.category = (entity.category != null && !entity.category.trim().isEmpty())
                ? entity.category.trim() : "默认";
        meta.favorite = entity.favorite;
        meta.pinned = entity.pinned;
        meta.hidden = entity.hidden;
        meta.deleted = entity.deleted;
        return meta;
    }
}
