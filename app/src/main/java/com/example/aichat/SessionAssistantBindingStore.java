package com.example.aichat;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

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
}
