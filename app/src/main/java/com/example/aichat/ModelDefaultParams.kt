package com.example.aichat

/**
 * 单个模型的默认参数。所有字段可空 — 空表示「未设置，沿用上游默认」。
 *
 * 解析顺序见 [ChatParamsResolver]：会话级覆盖 > 角色（Assistant）配置 > 模型默认 > 代码默认。
 */
data class ModelDefaultParams(
    @JvmField var temperature: Float? = null,
    @JvmField var topP: Float? = null,
    @JvmField var maxTokens: Int? = null,
    @JvmField var frequencyPenalty: Float? = null,
    @JvmField var presencePenalty: Float? = null,
    @JvmField var topK: Int? = null,
) {
    fun isEmpty(): Boolean = temperature == null && topP == null &&
            maxTokens == null && frequencyPenalty == null &&
            presencePenalty == null && topK == null
}
