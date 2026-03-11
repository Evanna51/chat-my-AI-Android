package com.example.aichat;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ProviderManager {
    private static final String PREFS = "aichat_providers";
    private static final String KEY_ENABLED_IDS = "enabled_provider_ids";
    private static final String KEY_PROVIDER_SETTINGS = "provider_settings";
    private static final Gson GSON = new Gson();
    private static final Type STRING_SET_TYPE = new TypeToken<Set<String>>(){}.getType();
    private static final Type SETTINGS_MAP_TYPE = new TypeToken<Map<String, ProviderSettings>>(){}.getType();

    private final Context context;

    public static class ProviderSettings {
        public String apiHost;
        public String apiPath;
        public String apiKey;
        public List<ProviderInfo.ProviderModelInfo> models;
    }

    public ProviderManager(Context context) {
        this.context = context.getApplicationContext();
    }

    private Set<String> getEnabledIds() {
        String json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENABLED_IDS, "[]");
        List<String> list = GSON.fromJson(json, List.class);
        return list != null ? new HashSet<>(list) : new HashSet<>();
    }

    private void saveEnabledIds(Set<String> ids) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ENABLED_IDS, GSON.toJson(new ArrayList<>(ids)))
                .apply();
    }

    public Map<String, ProviderSettings> getProviderSettingsMap() {
        String json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROVIDER_SETTINGS, "{}");
        Map<String, ProviderSettings> map = GSON.fromJson(json, SETTINGS_MAP_TYPE);
        Map<String, ProviderSettings> safeMap = map != null ? map : new HashMap<>();
        if (normalizeSettingsMapInPlace(safeMap)) {
            saveProviderSettingsMap(safeMap);
        }
        return safeMap;
    }

    private void saveProviderSettingsMap(Map<String, ProviderSettings> map) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PROVIDER_SETTINGS, GSON.toJson(map))
                .apply();
    }

    private boolean normalizeSettingsMapInPlace(Map<String, ProviderSettings> map) {
        boolean changed = false;
        for (Map.Entry<String, ProviderSettings> e : map.entrySet()) {
            ProviderSettings s = e.getValue();
            if (s == null) continue;
            String oldHost = s.apiHost != null ? s.apiHost : "";
            String oldPath = s.apiPath != null ? s.apiPath : "";

            if (oldHost.isEmpty()) {
                if ("/v1/chat/completions".equals(oldPath)) {
                    s.apiPath = "/chat/completions";
                    changed = true;
                }
                continue;
            }

            ApiUtils.HostAndPath hp = ApiUtils.normalizeOpenAIApiHostAndPath(oldHost, oldPath);
            String newHost = hp != null && hp.apiHost != null ? hp.apiHost : oldHost;
            String newPath = hp != null && hp.apiPath != null ? hp.apiPath : oldPath;
            if (!newHost.equals(oldHost) || !newPath.equals(oldPath)) {
                s.apiHost = newHost;
                s.apiPath = newPath;
                changed = true;
            }
        }
        return changed;
    }

    public List<ProviderInfo> getAllProviders() {
        Set<String> enabled = getEnabledIds();
        Map<String, ProviderSettings> settings = getProviderSettingsMap();
        List<ProviderInfo> result = new ArrayList<>();
        for (String id : getOrderedIds()) {
            if (!enabled.contains(id)) continue;
            ProviderCatalog.CatalogItem catalog = ProviderCatalog.get(id);
            if (catalog == null) continue;
            ProviderSettings s = settings.get(id);
            ProviderInfo p = toProviderInfo(catalog, s);
            result.add(p);
        }
        return result;
    }

    private List<String> getOrderedIds() {
        List<String> order = new ArrayList<>();
        for (ProviderCatalog.CatalogItem item : ProviderCatalog.getAll()) {
            order.add(item.id);
        }
        return order;
    }

    private ProviderInfo toProviderInfo(ProviderCatalog.CatalogItem catalog, ProviderSettings s) {
        ProviderInfo p = new ProviderInfo();
        p.id = catalog.id;
        p.name = catalog.name;
        p.type = "openai";
        p.isCustom = "custom".equals(catalog.id);
        p.apiHost = (s != null && s.apiHost != null && !s.apiHost.isEmpty()) ? s.apiHost : catalog.apiHost;
        p.apiPath = (s != null && s.apiPath != null && !s.apiPath.isEmpty()) ? s.apiPath : catalog.apiPath;
        if (p.apiPath == null || p.apiPath.isEmpty()) p.apiPath = "/chat/completions";
        p.apiKey = s != null && s.apiKey != null ? s.apiKey : "";
        p.models = (s != null && s.models != null) ? s.models : new ArrayList<>();
        return p;
    }

    /** 添加预设厂商 */
    public void addPresetProvider(String providerId) {
        Set<String> enabled = getEnabledIds();
        enabled.add(providerId);
        saveEnabledIds(enabled);
        ProviderCatalog.CatalogItem catalog = ProviderCatalog.get(providerId);
        if (catalog != null) {
            Map<String, ProviderSettings> map = getProviderSettingsMap();
            if (!map.containsKey(providerId)) {
                ProviderSettings s = new ProviderSettings();
                s.apiHost = catalog.apiHost;
                s.apiPath = catalog.apiPath;
                s.apiKey = "";
                s.models = new ArrayList<>();
                map.put(providerId, s);
                saveProviderSettingsMap(map);
            }
        }
    }

    /** 是否已添加 */
    public boolean isEnabled(String providerId) {
        return getEnabledIds().contains(providerId);
    }

    /** 删除厂商 */
    public void removeProvider(String providerId) {
        Set<String> enabled = getEnabledIds();
        enabled.remove(providerId);
        saveEnabledIds(enabled);
        Map<String, ProviderSettings> settings = getProviderSettingsMap();
        settings.remove(providerId);
        saveProviderSettingsMap(settings);
    }

    public void updateProviderSettings(String providerId, ProviderSettings s) {
        Map<String, ProviderSettings> map = getProviderSettingsMap();
        map.put(providerId, s);
        saveProviderSettingsMap(map);
    }

    public ProviderInfo getProvider(String providerId) {
        for (ProviderInfo p : getAllProviders()) {
            if (providerId.equals(p.id)) return p;
        }
        return null;
    }
}
