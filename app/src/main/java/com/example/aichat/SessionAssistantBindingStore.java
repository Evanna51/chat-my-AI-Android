package com.example.aichat;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.List;

public class SessionAssistantBindingStore {
    private static final String PREFS = "aichat_session_assistant_bindings";
    private static final String KEY_MAP = "session_assistant_map";
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>(){}.getType();

    private final SharedPreferences prefs;

    public SessionAssistantBindingStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Map<String, String> getMap() {
        String json = prefs.getString(KEY_MAP, "{}");
        Map<String, String> map = GSON.fromJson(json, MAP_TYPE);
        return map != null ? map : new HashMap<>();
    }

    private void saveMap(Map<String, String> map) {
        prefs.edit().putString(KEY_MAP, GSON.toJson(map)).apply();
    }

    public void bind(String sessionId, String assistantId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        Map<String, String> map = getMap();
        if (assistantId == null || assistantId.isEmpty()) map.remove(sessionId);
        else map.put(sessionId, assistantId);
        saveMap(map);
    }

    public String getAssistantId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return "";
        return getMap().getOrDefault(sessionId, "");
    }

    public void remove(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        bind(sessionId, null);
    }

    public boolean containsSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) return false;
        return getMap().containsKey(sessionId.trim());
    }

    public List<String> getSessionIdsByAssistantId(String assistantId) {
        String target = assistantId != null ? assistantId.trim() : "";
        if (target.isEmpty()) return new ArrayList<>();
        Map<String, String> map = getMap();
        List<Map.Entry<String, String>> entries = new ArrayList<>(map.entrySet());
        Collections.sort(entries, Comparator.comparing(Map.Entry::getKey));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> one : entries) {
            if (one == null) continue;
            String sid = one.getKey();
            String aid = one.getValue();
            if (sid == null || sid.trim().isEmpty()) continue;
            if (target.equals(aid != null ? aid.trim() : "")) {
                out.add(sid.trim());
            }
        }
        return out;
    }
}
