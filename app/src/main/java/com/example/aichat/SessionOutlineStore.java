package com.example.aichat;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class SessionOutlineStore {
    private static final String PREFS = "aichat_session_outlines";
    private static final String KEY_PREFIX = "outlines_";
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<SessionOutlineItem>>() {}.getType();

    private final SharedPreferences prefs;

    public SessionOutlineStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<SessionOutlineItem> getAll(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) return new ArrayList<>();
        String raw = prefs.getString(KEY_PREFIX + sessionId, "[]");
        List<SessionOutlineItem> list = GSON.fromJson(raw, LIST_TYPE);
        if (list == null) list = new ArrayList<>();
        Collections.sort(list, Comparator.comparingLong(a -> a != null ? a.createdAt : 0L));
        return list;
    }

    public void saveAll(String sessionId, List<SessionOutlineItem> items) {
        if (sessionId == null || sessionId.trim().isEmpty()) return;
        List<SessionOutlineItem> safe = items != null ? items : new ArrayList<>();
        prefs.edit().putString(KEY_PREFIX + sessionId, GSON.toJson(safe)).apply();
    }

    public SessionOutlineItem add(String sessionId, String type, String title, String content) {
        List<SessionOutlineItem> list = getAll(sessionId);
        SessionOutlineItem item = new SessionOutlineItem();
        long now = System.currentTimeMillis();
        item.id = UUID.randomUUID().toString();
        item.type = normalizeType(type);
        item.title = title != null ? title.trim() : "";
        item.content = content != null ? content.trim() : "";
        item.createdAt = now;
        item.updatedAt = now;
        list.add(item);
        saveAll(sessionId, list);
        return item;
    }

    public void update(String sessionId, SessionOutlineItem updated) {
        if (updated == null || updated.id == null) return;
        List<SessionOutlineItem> list = getAll(sessionId);
        for (SessionOutlineItem one : list) {
            if (one != null && updated.id.equals(one.id)) {
                one.type = normalizeType(updated.type);
                one.title = updated.title != null ? updated.title.trim() : "";
                one.content = updated.content != null ? updated.content.trim() : "";
                one.updatedAt = System.currentTimeMillis();
                break;
            }
        }
        saveAll(sessionId, list);
    }

    public void delete(String sessionId, String itemId) {
        if (itemId == null || itemId.trim().isEmpty()) return;
        List<SessionOutlineItem> list = getAll(sessionId);
        List<SessionOutlineItem> out = new ArrayList<>();
        for (SessionOutlineItem one : list) {
            if (one == null || one.id == null || one.id.equals(itemId)) continue;
            out.add(one);
        }
        saveAll(sessionId, out);
    }

    public int nextChapterIndex(String sessionId) {
        int max = 0;
        List<SessionOutlineItem> list = getAll(sessionId);
        for (SessionOutlineItem one : list) {
            if (one == null) continue;
            if (!"chapter".equals(normalizeType(one.type))) continue;
            String t = one.title != null ? one.title : "";
            int idx = parseChapterIndex(t);
            if (idx > max) max = idx;
        }
        return max + 1;
    }

    public String normalizeType(String type) {
        if ("chapter".equals(type)
                || "material".equals(type)
                || "task".equals(type)
                || "world".equals(type)
                || "knowledge".equals(type)) {
            return type;
        }
        return "chapter";
    }

    private int parseChapterIndex(String title) {
        if (title == null || title.trim().isEmpty()) return 0;
        String t = title.trim();
        String digits = t.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (Exception ignored) {
            return 0;
        }
    }
}
