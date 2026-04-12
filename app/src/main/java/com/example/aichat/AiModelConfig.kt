package com.example.aichat

import android.content.Context

/**
 * AI 模型配置中心。从 ModelConfig 取各任务选用的预设，解析为完整 API 配置；
 * 聊天、话题命名、搜索、总结等实际调用时从此类读取。
 */
class AiModelConfig(context: Context) {

    companion object {
        const val TASK_CHAT = "chat"
        const val TASK_THREAD_NAMING = "thread_naming"
        const val TASK_SEARCH = "search"
        const val TASK_SUMMARY = "summary"
        const val TASK_NOVEL_SHARP = "novel_sharp"
    }

    /** 解析后的完整 API 配置 */
    class ResolvedConfig(
        @JvmField var apiHost: String,
        apiPath: String?,
        apiKey: String?,
        modelId: String?
    ) {
        @JvmField var apiPath: String = apiPath ?: "/chat/completions"
        @JvmField var apiKey: String = apiKey ?: ""
        @JvmField var modelId: String = modelId ?: ""

        fun isValid(): Boolean =
            apiHost.isNotEmpty() && modelId.isNotEmpty()

        /** 用于 Retrofit 的 baseUrl（仅 host，因 ChatApi 已带 chat/completions 路径） */
        fun toRetrofitBaseUrl(): String {
            return try {
                val host = ApiUtils.toModelsBaseUrl(apiHost, apiPath)
                if (host.isEmpty()) return "https://api.openai.com/v1/"
                if (host.endsWith("/")) host else "$host/"
            } catch (e: Exception) {
                "https://api.openai.com/v1/"
            }
        }
    }

    private val modelConfig: ModelConfig = ModelConfig(context).also { it.migrateFromConfigManager() }
    private val configManager: ConfigManager = ConfigManager(context)
    private val providerManager: ProviderManager = ProviderManager(context)

    /** 获取聊天用配置 */
    fun getConfigForChat(): ResolvedConfig = resolve(modelConfig.getChatPreset())

    /** 获取话题命名用配置 */
    fun getConfigForThreadNaming(): ResolvedConfig = resolve(modelConfig.getThreadNamingPreset())

    /** 获取搜索用配置 */
    fun getConfigForSearch(): ResolvedConfig = resolve(modelConfig.getSearchPreset())

    /** 获取总结用配置 */
    fun getConfigForSummary(): ResolvedConfig = resolve(modelConfig.getSummaryPreset())

    /** 获取小说敏锐用配置 */
    fun getConfigForNovelSharp(): ResolvedConfig = resolve(modelConfig.getNovelSharpPreset())

    /** 按任务类型获取配置 */
    fun getConfigForTask(taskType: String): ResolvedConfig {
        return when (taskType) {
            TASK_CHAT -> getConfigForChat()
            TASK_THREAD_NAMING -> getConfigForThreadNaming()
            TASK_SEARCH -> getConfigForSearch()
            TASK_SUMMARY -> getConfigForSummary()
            TASK_NOVEL_SHARP -> getConfigForNovelSharp()
            else -> getConfigForChat()
        }
    }

    /**
     * 将 preset key (providerId:modelId) 解析为完整配置。
     * 内置回退：若 key 为空，取第一个已配置模型；若仍无效，则用 ConfigManager 的旧配置。
     */
    private fun resolve(modelKey: String?): ResolvedConfig {
        var key = modelKey
        if (key.isNullOrEmpty()) {
            key = modelConfig.getFirstAvailablePreset()
        }
        if (!key.isNullOrEmpty() && key.contains(":")) {
            val i = key.indexOf(':')
            val providerId = key.substring(0, i)
            val modelId = key.substring(i + 1)
            val p = providerManager.getProvider(providerId)
            if (p != null) {
                val host = p.apiHost ?: ""
                val path = if (!p.apiPath.isNullOrEmpty()) p.apiPath else "/chat/completions"
                val apiKey = p.apiKey ?: ""
                return ResolvedConfig(host, path, apiKey, modelId)
            }
        }
        // 回退：使用 ConfigManager 的旧配置（兼容未迁移用户）
        val host = configManager.getApiUrl()
        val path = "/chat/completions"
        val apiKey = configManager.getApiKey()
        val model = if (!key.isNullOrEmpty()) key else configManager.getModel()
        return ResolvedConfig(host, path, apiKey, model)
    }
}
