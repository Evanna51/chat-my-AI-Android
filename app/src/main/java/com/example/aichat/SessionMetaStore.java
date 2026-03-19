package com.example.aichat;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SessionMetaStore {
    private static final String PREFS = "aichat_session_meta";
    private static final String KEY_MAP = "session_meta_map";
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, SessionMeta>>() {}.getType();

    private final SharedPreferences prefs;

    public SessionMetaStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Map<String, SessionMeta> getMap() {
        String json = prefs.getString(KEY_MAP, "{}");
        Map<String, SessionMeta> map = GSON.fromJson(json, MAP_TYPE);
        return map != null ? map : new HashMap<>();
    }

    private void saveMap(Map<String, SessionMeta> map) {
        prefs.edit().putString(KEY_MAP, GSON.toJson(map)).apply();
    }

    public SessionMeta get(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return new SessionMeta();
        Map<String, SessionMeta> map = getMap();
        SessionMeta meta = map.get(sessionId);
        return meta != null ? meta : new SessionMeta();
    }

    public Map<String, SessionMeta> getAll() {
        return getMap();
    }

    public void save(String sessionId, SessionMeta meta) {
        if (sessionId == null || sessionId.isEmpty() || meta == null) return;
        Map<String, SessionMeta> map = getMap();
        map.put(sessionId, meta);
        saveMap(map);
    }

    public void remove(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        Map<String, SessionMeta> map = getMap();
        if (map.remove(sessionId) != null) {
            saveMap(map);
        }
    }

    public void updateTitle(String sessionId, String title) {
        SessionMeta meta = get(sessionId);
        meta.title = title != null ? title.trim() : "";
        save(sessionId, meta);
    }

    public void updateCategory(String sessionId, String category) {
        SessionMeta meta = get(sessionId);
        meta.category = (category != null && !category.trim().isEmpty()) ? category.trim() : "默认";
        save(sessionId, meta);
    }

    public void setFavorite(String sessionId, boolean favorite) {
        SessionMeta meta = get(sessionId);
        meta.favorite = favorite;
        if (favorite) meta.category = "收藏";
        save(sessionId, meta);
    }

    public void setPinned(String sessionId, boolean pinned) {
        SessionMeta meta = get(sessionId);
        meta.pinned = pinned;
        save(sessionId, meta);
    }

    public void setDeleted(String sessionId, boolean deleted) {
        SessionMeta meta = get(sessionId);
        meta.deleted = deleted;
        save(sessionId, meta);
    }

    public void setHidden(String sessionId, boolean hidden) {
        SessionMeta meta = get(sessionId);
        meta.hidden = hidden;
        save(sessionId, meta);
    }

    public void updateOutline(String sessionId, String outline) {
        SessionMeta meta = get(sessionId);
        meta.outline = outline != null ? outline.trim() : "";
        save(sessionId, meta);
    }

    public List<String> getAllCategories() {
        Set<String> categories = new LinkedHashSet<>();
        categories.add("默认");
        categories.add("工作");
        categories.add("生活");
        categories.add("收藏");
        categories.add("学习");
        Map<String, SessionMeta> map = getMap();
        for (SessionMeta meta : map.values()) {
            if (meta == null) continue;
            String category = meta.category != null ? meta.category.trim() : "";
            if (!category.isEmpty()) categories.add(category);
        }
        return new java.util.ArrayList<>(categories);
    }
}
