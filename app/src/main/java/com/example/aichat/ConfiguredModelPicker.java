package com.example.aichat;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * 已配置的模型：厂商已设置 API Key（或本地厂商），且用户已添加该模型。
 * 用于模型配置选择器等。
 */
public class ConfiguredModelPicker {

    public static class Option {
        public String providerId;
        public String providerName;
        public String modelId;
        public String displayName;  // nickname or modelId

        /** 存储格式，用于 ConfigManager */
        public String getStorageKey() {
            return providerId + ":" + modelId;
        }

        public static Option fromStorageKey(String key, Context ctx) {
            if (key == null || !key.contains(":")) return null;
            int i = key.indexOf(':');
            String pid = key.substring(0, i);
            String mid = key.substring(i + 1);
            ProviderManager pm = new ProviderManager(ctx);
            ProviderInfo p = pm.getProvider(pid);
            if (p == null) return null;
            if (p.models == null) return null;
            for (ProviderInfo.ProviderModelInfo m : p.models) {
                if (m != null && mid.equals(m.modelId)) {
                    Option o = new Option();
                    o.providerId = pid;
                    o.providerName = p.name;
                    o.modelId = mid;
                    o.displayName = m.nickname != null && !m.nickname.isEmpty() ? m.nickname : mid;
                    return o;
                }
            }
            Option o = new Option();
            o.providerId = pid;
            o.providerName = p.name;
            o.modelId = mid;
            o.displayName = mid;
            return o;
        }
    }

    /** 获取所有可选的已配置模型 */
    public static List<Option> getConfiguredModels(Context ctx) {
        ProviderManager pm = new ProviderManager(ctx);
        List<ProviderInfo> providers = pm.getAllProviders();
        List<Option> result = new ArrayList<>();
        ProviderCatalog.CatalogItem cat;
        for (ProviderInfo p : providers) {
            cat = ProviderCatalog.get(p.id);
            boolean needsKey = cat == null || cat.needsKey;
            boolean hasHost = p.apiHost != null && !p.apiHost.isEmpty();
            boolean hasKey = !needsKey || (p.apiKey != null && !p.apiKey.isEmpty());
            if (!hasHost || !hasKey) continue;
            if (p.models == null || p.models.isEmpty()) continue;
            for (ProviderInfo.ProviderModelInfo m : p.models) {
                if (m.modelId == null) continue;
                Option o = new Option();
                o.providerId = p.id;
                o.providerName = p.name;
                o.modelId = m.modelId;
                o.displayName = m.nickname != null && !m.nickname.isEmpty() ? m.nickname : m.modelId;
                result.add(o);
            }
        }
        return result;
    }
}
