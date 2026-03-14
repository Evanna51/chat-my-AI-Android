package com.example.aichat;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class ProactiveMessageSyncStore {
    private static final String PREFS = "aichat_proactive_sync";
    private static final String KEY_LAST_SINCE = "last_since";
    private static final String KEY_PROCESSED_IDS = "processed_ids";
    private static final long PROCESSED_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_TRACKED_IDS = 500;
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Long>>() {}.getType();

    private final SharedPreferences prefs;

    public ProactiveMessageSyncStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized String getLastSince() {
        return safeTrim(prefs.getString(KEY_LAST_SINCE, ""));
    }

    public synchronized void setLastSince(String since) {
        prefs.edit().putString(KEY_LAST_SINCE, safeTrim(since)).apply();
    }

    public synchronized boolean isRecentlyProcessed(String messageId) {
        String id = safeTrim(messageId);
        if (id.isEmpty()) return false;
        Map<String, Long> map = getProcessedMap();
        pruneExpired(map, System.currentTimeMillis());
        saveProcessedMap(map);
        return map.containsKey(id);
    }

    public synchronized void markProcessed(String messageId) {
        String id = safeTrim(messageId);
        if (id.isEmpty()) return;
        long now = System.currentTimeMillis();
        Map<String, Long> map = getProcessedMap();
        pruneExpired(map, now);
        map.put(id, now);
        if (map.size() > MAX_TRACKED_IDS) {
            dropOldest(map);
        }
        saveProcessedMap(map);
    }

    private Map<String, Long> getProcessedMap() {
        String json = prefs.getString(KEY_PROCESSED_IDS, "{}");
        Map<String, Long> map = GSON.fromJson(json, MAP_TYPE);
        return map != null ? map : new HashMap<>();
    }

    private void saveProcessedMap(Map<String, Long> map) {
        prefs.edit().putString(KEY_PROCESSED_IDS, GSON.toJson(map)).apply();
    }

    private void pruneExpired(Map<String, Long> map, long now) {
        Iterator<Map.Entry<String, Long>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> one = it.next();
            if (one == null || one.getKey() == null || one.getKey().trim().isEmpty()) {
                it.remove();
                continue;
            }
            Long ts = one.getValue();
            if (ts == null || now - ts > PROCESSED_TTL_MS) {
                it.remove();
            }
        }
    }

    private void dropOldest(Map<String, Long> map) {
        String oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Long> one : map.entrySet()) {
            if (one == null || one.getKey() == null) continue;
            long ts = one.getValue() != null ? one.getValue() : Long.MAX_VALUE;
            if (ts < oldest) {
                oldest = ts;
                oldestKey = one.getKey();
            }
        }
        if (oldestKey != null) map.remove(oldestKey);
    }

    private String safeTrim(String text) {
        return text != null ? text.trim() : "";
    }
}
