package com.example.aichat

import android.content.Context

/**
 * 参数回退链：会话覆盖 → 角色（Assistant）配置 → 模型默认 → 代码默认。
 *
 * - 会话级 [SessionChatOptions]：UI 上让用户填空表示「跟随上游」（仅对可空字段生效；
 *   temperature/topP 现阶段保持非空，相当于会话一旦保存就有具体值，不再回退）。
 * - 角色：[MyAssistant.options] —— 同样的 SessionChatOptions 结构，会话第一次创建时
 *   ([ChatSessionActivity.initializeSessionOptionsFromAssistantOrGlobal]) 已经把它复制到
 *   会话；这里再补一层运行时回退，主要给「可空字段」服务（角色配置中没填 maxTokens
 *   时不会把会话已有的值清掉）。
 * - 模型默认：[ProviderInfo.ProviderModelInfo.defaultParams]。
 * - 代码默认：调用方提供（如 ChatService 给小说大纲调用写死了 0.2 / 0.7）。
 */
object ChatParamsResolver {

    /** 调用结果。所有字段非空 — 除非调用方代码默认也没给。 */
    data class Resolved(
        val temperature: Float?,
        val topP: Float?,
        val maxTokens: Int?,
        val frequencyPenalty: Float?,
        val presencePenalty: Float?,
        val topK: Int?,
    )

    /**
     * 通用解析。`session` 可空（无会话上下文场景，如小说大纲、嵌入）；
     * `assistant` 可空；`model` 可空；`codeDefault` 是调用方写死的兜底值。
     */
    fun resolve(
        session: SessionChatOptions?,
        assistant: SessionChatOptions?,
        model: ModelDefaultParams?,
        codeDefault: ModelDefaultParams = ModelDefaultParams(),
    ): Resolved {
        // temperature/topP：会话存的是非空 primitive；只有当 session==null 才会真正回退。
        val temperature = session?.temperature
            ?: assistant?.temperature
            ?: model?.temperature
            ?: codeDefault.temperature
        val topP = session?.topP
            ?: assistant?.topP
            ?: model?.topP
            ?: codeDefault.topP

        val maxTokens = session?.maxTokens
            ?: assistant?.maxTokens
            ?: model?.maxTokens
            ?: codeDefault.maxTokens
        val freq = session?.frequencyPenalty
            ?: assistant?.frequencyPenalty
            ?: model?.frequencyPenalty
            ?: codeDefault.frequencyPenalty
        val pres = session?.presencePenalty
            ?: assistant?.presencePenalty
            ?: model?.presencePenalty
            ?: codeDefault.presencePenalty
        val topK = session?.topK
            ?: assistant?.topK
            ?: model?.topK
            ?: codeDefault.topK
        return Resolved(temperature, topP, maxTokens, freq, pres, topK)
    }

    /**
     * 通过 modelKey（"providerId:modelId" 格式）找到对应模型的默认参数。
     * 找不到（厂商/模型已删除、key 为空、key 格式不对）返回 null。
     */
    fun lookupModelDefaults(context: Context, modelKey: String?): ModelDefaultParams? {
        if (modelKey.isNullOrEmpty()) return null
        val idx = modelKey.indexOf(':')
        if (idx <= 0 || idx >= modelKey.length - 1) return null
        val providerId = modelKey.substring(0, idx)
        val modelId = modelKey.substring(idx + 1)
        val provider = ProviderManager(context).getProvider(providerId) ?: return null
        val model = provider.models.firstOrNull { it.modelId == modelId } ?: return null
        return model.defaultParams
    }

    /**
     * 把回退结果应用到 ChatRequest 上 — 仅在解析出的值非 null 时设置。
     * 这样调用方可以先填好「写死的 codeDefault」再调一次 apply，让用户的模型默认
     * 覆盖写死的值。
     */
    fun applyTo(request: ChatApi.ChatRequest, resolved: Resolved) {
        resolved.temperature?.let { request.temperature = it.toDouble() }
        resolved.topP?.let { request.topP = it.toDouble() }
        resolved.maxTokens?.let { request.maxTokens = it }
        resolved.frequencyPenalty?.let { request.frequencyPenalty = it.toDouble() }
        resolved.presencePenalty?.let { request.presencePenalty = it.toDouble() }
        resolved.topK?.let { request.topK = it }
    }
}
