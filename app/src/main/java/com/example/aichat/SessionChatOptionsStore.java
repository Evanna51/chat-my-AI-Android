package com.example.aichat;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class SessionChatOptionsStore {
    private static final String PREFS = "aichat_session_chat_options";
    private static final String KEY_MAP = "session_options_map";
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, SessionChatOptions>>(){}.getType();

    private final SharedPreferences prefs;

    public SessionChatOptionsStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Map<String, SessionChatOptions> getMap() {
        String json = prefs.getString(KEY_MAP, "{}");
        Map<String, SessionChatOptions> map = GSON.fromJson(json, MAP_TYPE);
        return map != null ? map : new HashMap<>();
    }

    private void saveMap(Map<String, SessionChatOptions> map) {
        prefs.edit().putString(KEY_MAP, GSON.toJson(map)).apply();
    }

    public SessionChatOptions get(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return new SessionChatOptions();
        Map<String, SessionChatOptions> map = getMap();
        SessionChatOptions options = map.get(sessionId);
        SessionChatOptions out = options != null ? options : new SessionChatOptions();
        out.streamOutput = true;
        return out;
    }

    public boolean has(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return false;
        Map<String, SessionChatOptions> map = getMap();
        return map.containsKey(sessionId);
    }

    public void save(String sessionId, SessionChatOptions options) {
        if (sessionId == null || sessionId.isEmpty() || options == null) return;
        options.streamOutput = true;
        Map<String, SessionChatOptions> map = getMap();
        map.put(sessionId, options);
        saveMap(map);
    }

    public void remove(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        Map<String, SessionChatOptions> map = getMap();
        if (map.remove(sessionId) != null) {
            saveMap(map);
        }
    }
}
