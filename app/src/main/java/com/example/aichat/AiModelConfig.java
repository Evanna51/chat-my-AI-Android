package com.example.aichat;

import android.content.Context;

/**
 * AI 模型配置中心。从 ModelConfig 取各任务选用的预设，解析为完整 API 配置；
 * 聊天、话题命名、搜索、总结等实际调用时从此类读取。
 */
public class AiModelConfig {

    public static final String TASK_CHAT = "chat";
    public static final String TASK_THREAD_NAMING = "thread_naming";
    public static final String TASK_SEARCH = "search";
    public static final String TASK_SUMMARY = "summary";
    public static final String TASK_NOVEL_SHARP = "novel_sharp";

    /** 解析后的完整 API 配置 */
    public static class ResolvedConfig {
        public String apiHost;
        public String apiPath;
        public String apiKey;
        public String modelId;

        public ResolvedConfig(String apiHost, String apiPath, String apiKey, String modelId) {
            this.apiHost = apiHost;
            this.apiPath = apiPath != null ? apiPath : "/chat/completions";
            this.apiKey = apiKey != null ? apiKey : "";
            this.modelId = modelId != null ? modelId : "";
        }

        public boolean isValid() {
            return apiHost != null && !apiHost.isEmpty() && modelId != null && !modelId.isEmpty();
        }

        /** 用于 Retrofit 的 baseUrl（仅 host，因 ChatApi 已带 chat/completions 路径） */
        public String toRetrofitBaseUrl() {
            try {
                String host = ApiUtils.toModelsBaseUrl(apiHost, apiPath);
                if (host == null || host.isEmpty()) return "https://api.openai.com/v1/";
                return host.endsWith("/") ? host : host + "/";
            } catch (Exception e) {
                return "https://api.openai.com/v1/";
            }
        }
    }

    private final ModelConfig modelConfig;
    private final ConfigManager configManager;
    private final ProviderManager providerManager;

    public AiModelConfig(Context context) {
        modelConfig = new ModelConfig(context);
        modelConfig.migrateFromConfigManager();
        configManager = new ConfigManager(context);
        providerManager = new ProviderManager(context);
    }

    /** 获取聊天用配置 */
    public ResolvedConfig getConfigForChat() {
        return resolve(modelConfig.getChatPreset());
    }

    /** 获取话题命名用配置 */
    public ResolvedConfig getConfigForThreadNaming() {
        return resolve(modelConfig.getThreadNamingPreset());
    }

    /** 获取搜索用配置 */
    public ResolvedConfig getConfigForSearch() {
        return resolve(modelConfig.getSearchPreset());
    }

    /** 获取总结用配置 */
    public ResolvedConfig getConfigForSummary() {
        return resolve(modelConfig.getSummaryPreset());
    }

    /** 获取小说敏锐用配置 */
    public ResolvedConfig getConfigForNovelSharp() {
        return resolve(modelConfig.getNovelSharpPreset());
    }

    /** 按任务类型获取配置 */
    public ResolvedConfig getConfigForTask(String taskType) {
        if (TASK_CHAT.equals(taskType)) return getConfigForChat();
        if (TASK_THREAD_NAMING.equals(taskType)) return getConfigForThreadNaming();
        if (TASK_SEARCH.equals(taskType)) return getConfigForSearch();
        if (TASK_SUMMARY.equals(taskType)) return getConfigForSummary();
        if (TASK_NOVEL_SHARP.equals(taskType)) return getConfigForNovelSharp();
        return getConfigForChat();
    }

    /**
     * 将 preset key (providerId:modelId) 解析为完整配置。
     * 内置回退：若 key 为空，取第一个已配置模型；若仍无效，则用 ConfigManager 的旧配置。
     */
    private ResolvedConfig resolve(String modelKey) {
        if (modelKey == null || modelKey.isEmpty()) {
            modelKey = modelConfig.getFirstAvailablePreset();
        }
        if (modelKey != null && modelKey.contains(":")) {
            int i = modelKey.indexOf(':');
            String providerId = modelKey.substring(0, i);
            String modelId = modelKey.substring(i + 1);
            ProviderInfo p = providerManager.getProvider(providerId);
            if (p != null) {
                String host = p.apiHost != null ? p.apiHost : "";
                String path = p.apiPath != null && !p.apiPath.isEmpty() ? p.apiPath : "/chat/completions";
                String key = p.apiKey != null ? p.apiKey : "";
                return new ResolvedConfig(host, path, key, modelId);
            }
        }
        // 回退：使用 ConfigManager 的旧配置（兼容未迁移用户）
        String host = configManager.getApiUrl();
        String path = "/chat/completions";
        String key = configManager.getApiKey();
        String model = (modelKey != null && !modelKey.isEmpty()) ? modelKey : configManager.getModel();
        return new ResolvedConfig(host, path, key, model);
    }
}
