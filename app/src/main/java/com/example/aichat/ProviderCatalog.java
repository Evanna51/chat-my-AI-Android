package com.example.aichat;

import java.util.ArrayList;
import java.util.List;

/**
 * 厂商预设目录，选择即用，省去配置
 */
public class ProviderCatalog {
    public static final String CATEGORY_CN = "国内";
    public static final String CATEGORY_LOCAL = "本地";
    public static final String CATEGORY_INTL = "国际";
    public static final String CATEGORY_OTHER = "其他";

    public static class CatalogItem {
        public String id;
        public String name;
        public String category;
        public String apiHost;
        public String apiPath;
        public boolean needsKey = true;  // 本地 Ollama/LMStudio 通常不需 key

        public CatalogItem(String id, String name, String category, String apiHost, String apiPath) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.apiHost = apiHost != null ? apiHost : "";
            this.apiPath = apiPath != null ? apiPath : "/chat/completions";
        }

        public CatalogItem(String id, String name, String category, String apiHost, String apiPath, boolean needsKey) {
            this(id, name, category, apiHost, apiPath);
            this.needsKey = needsKey;
        }
    }

    private static final List<CatalogItem> CATALOG = new ArrayList<>();
    static {
        // 国内
        CATALOG.add(new CatalogItem("deepseek", "DeepSeek", CATEGORY_CN, "https://api.deepseek.com/v1", ""));
        CATALOG.add(new CatalogItem("siliconflow", "SiliconFlow", CATEGORY_CN, "https://api.siliconflow.cn/v1", ""));
        CATALOG.add(new CatalogItem("zhipu", "智谱 GLM", CATEGORY_CN, "https://open.bigmodel.cn/api/paas/v4", ""));
        CATALOG.add(new CatalogItem("qwen", "通义千问", CATEGORY_CN, "https://dashscope.aliyuncs.com/compatible-mode/v1", ""));

        // 本地
        CATALOG.add(new CatalogItem("ollama", "Ollama", CATEGORY_LOCAL, "http://127.0.0.1:11434/v1", "", false));
        CATALOG.add(new CatalogItem("llama", "Llama.cpp", CATEGORY_LOCAL, "http://127.0.0.1:8080/v1", "", false));
        CATALOG.add(new CatalogItem("lmstudio", "LM Studio", CATEGORY_LOCAL, "http://127.0.0.1:1234/v1", "", false));

        // 国际
        CATALOG.add(new CatalogItem("openai", "ChatGPT", CATEGORY_INTL, "https://api.openai.com/v1", ""));
        CATALOG.add(new CatalogItem("gemini", "Gemini", CATEGORY_INTL, "https://generativelanguage.googleapis.com/v1beta", ""));
        CATALOG.add(new CatalogItem("openrouter", "OpenRouter", CATEGORY_INTL, "https://openrouter.ai/api/v1", ""));

        // 其他
        CATALOG.add(new CatalogItem("custom", "通用 OpenAI 兼容", CATEGORY_OTHER, "", "/chat/completions"));
    }

    public static List<CatalogItem> getAll() {
        return new ArrayList<>(CATALOG);
    }

    public static CatalogItem get(String id) {
        for (CatalogItem item : CATALOG) {
            if (item.id.equals(id)) return item;
        }
        return null;
    }
}
