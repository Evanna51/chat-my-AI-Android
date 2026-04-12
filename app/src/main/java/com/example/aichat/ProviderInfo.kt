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
        @JvmField var nickname: String = ""
    ) {
        constructor(modelId: String) : this(modelId = modelId, nickname = "")
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
