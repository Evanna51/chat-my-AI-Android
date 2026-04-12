package com.example.aichat

/**
 * 厂商预设目录，选择即用，省去配置
 */
class ProviderCatalog {

    class CatalogItem @JvmOverloads constructor(
        @JvmField var id: String,
        @JvmField var name: String,
        @JvmField var category: String,
        @JvmField var apiHost: String,
        @JvmField var apiPath: String,
        @JvmField var needsKey: Boolean = true  // 本地 Ollama/LMStudio 通常不需 key
    ) {
        init {
            this.apiHost = apiHost ?: ""
            this.apiPath = apiPath ?: "/chat/completions"
        }
    }

    companion object {
        const val CATEGORY_CN = "国内"
        const val CATEGORY_LOCAL = "本地"
        const val CATEGORY_INTL = "国际"
        const val CATEGORY_OTHER = "其他"

        private val CATALOG = mutableListOf<CatalogItem>()

        init {
            // 国内
            CATALOG.add(CatalogItem("deepseek", "DeepSeek", CATEGORY_CN, "https://api.deepseek.com/v1", ""))
            CATALOG.add(CatalogItem("siliconflow", "SiliconFlow", CATEGORY_CN, "https://api.siliconflow.cn/v1", ""))
            CATALOG.add(CatalogItem("zhipu", "智谱 GLM", CATEGORY_CN, "https://open.bigmodel.cn/api/paas/v4", ""))
            CATALOG.add(CatalogItem("qwen", "通义千问", CATEGORY_CN, "https://dashscope.aliyuncs.com/compatible-mode/v1", ""))

            // 本地
            CATALOG.add(CatalogItem("ollama", "Ollama", CATEGORY_LOCAL, "http://127.0.0.1:11434/v1", "", false))
            CATALOG.add(CatalogItem("llama", "Llama.cpp", CATEGORY_LOCAL, "http://127.0.0.1:8080/v1", "", false))
            CATALOG.add(CatalogItem("lmstudio", "LM Studio", CATEGORY_LOCAL, "http://127.0.0.1:1234/v1", "", false))

            // 国际
            CATALOG.add(CatalogItem("openai", "ChatGPT", CATEGORY_INTL, "https://api.openai.com/v1", ""))
            CATALOG.add(CatalogItem("gemini", "Gemini", CATEGORY_INTL, "https://generativelanguage.googleapis.com/v1beta", ""))
            CATALOG.add(CatalogItem("openrouter", "OpenRouter", CATEGORY_INTL, "https://openrouter.ai/api/v1", ""))

            // 其他
            CATALOG.add(CatalogItem("custom", "通用 OpenAI 兼容", CATEGORY_OTHER, "", "/chat/completions"))
        }

        @JvmStatic
        fun getAll(): List<CatalogItem> = ArrayList(CATALOG)

        @JvmStatic
        fun get(id: String): CatalogItem? {
            for (item in CATALOG) {
                if (item.id == id) return item
            }
            return null
        }
    }
}
