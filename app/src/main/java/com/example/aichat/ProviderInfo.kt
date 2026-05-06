package com.example.aichat

/**
 * 参考 chatbox 的 Provider 结构
 * @see ../chatbox/src/shared/types.ts ProviderBaseInfo, ProviderSettings
 */
class ProviderInfo {
    @JvmField var id: String = ""
    @JvmField var name: String = ""
    @JvmField var type: String = "openai" // ModelProviderType.OpenAI = openai
    @JvmField var isCustom: Boolean = false

    @JvmField var apiHost: String = ""
    @JvmField var apiPath: String = ""
    @JvmField var apiKey: String = ""
    @JvmField var models: MutableList<ProviderModelInfo> = mutableListOf()

    data class ProviderModelInfo(
        @JvmField var modelId: String = "",
        @JvmField var nickname: String = "",
        @JvmField var thinkingEnabled: Boolean = false,
        /**
         * 模型默认参数。Gson 序列化保存在 ProviderManager.KEY_PROVIDER_SETTINGS 里。
         * 老数据没有该字段时反序列化为 null，保持兼容。
         */
        @JvmField var defaultParams: ModelDefaultParams? = null,
    ) {
        constructor(modelId: String) : this(modelId = modelId, nickname = "", thinkingEnabled = false)
    }

    companion object {
        @JvmStatic
        fun createCustom(id: String, name: String): ProviderInfo {
            val p = ProviderInfo()
            p.id = id
            p.name = name
            p.type = "openai"
            p.isCustom = true
            p.apiHost = ""
            p.apiPath = "/chat/completions"
            p.apiKey = ""
            return p
        }
    }
}
