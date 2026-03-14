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

public class MyAssistantStore {
    private static final String PREFS = "aichat_my_assistants";
    private static final String KEY_LIST = "assistant_list";
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<MyAssistant>>(){}.getType();

    private final SharedPreferences prefs;

    public MyAssistantStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<MyAssistant> getAll() {
        String json = prefs.getString(KEY_LIST, "[]");
        List<MyAssistant> list = GSON.fromJson(json, LIST_TYPE);
        if (list == null) list = new ArrayList<>();
        Collections.sort(list, (a, b) -> Long.compare(b != null ? b.updatedAt : 0L, a != null ? a.updatedAt : 0L));
        return list;
    }

    public List<MyAssistant> getRecent(int n) {
        List<MyAssistant> all = getAll();
        if (all.size() <= n) return all;
        return new ArrayList<>(all.subList(0, n));
    }

    public MyAssistant getById(String id) {
        if (id == null) return null;
        for (MyAssistant a : getAll()) {
            if (a != null && id.equals(a.id)) return a;
        }
        return null;
    }

    public MyAssistant createEmpty() {
        MyAssistant a = new MyAssistant();
        a.id = UUID.randomUUID().toString();
        a.name = "新助手";
        a.prompt = "";
        a.avatar = "";
        a.avatarImageBase64 = "";
        a.type = "default";
        a.allowAutoLife = false;
        a.allowProactiveMessage = false;
        a.options = new SessionChatOptions();
        a.updatedAt = System.currentTimeMillis();
        return a;
    }

    public void save(MyAssistant assistant) {
        if (assistant == null) return;
        List<MyAssistant> list = getAll();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            MyAssistant old = list.get(i);
            if (old != null && old.id != null && old.id.equals(assistant.id)) {
                list.set(i, assistant);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(assistant);
        assistant.updatedAt = System.currentTimeMillis();
        prefs.edit().putString(KEY_LIST, GSON.toJson(list)).apply();
    }

    public void delete(String id) {
        if (id == null) return;
        List<MyAssistant> list = getAll();
        List<MyAssistant> out = new ArrayList<>();
        for (MyAssistant a : list) {
            if (a == null || a.id == null || !a.id.equals(id)) out.add(a);
        }
        prefs.edit().putString(KEY_LIST, GSON.toJson(out)).apply();
    }
}
