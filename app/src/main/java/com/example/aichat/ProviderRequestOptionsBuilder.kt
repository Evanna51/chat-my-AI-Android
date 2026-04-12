package com.example.aichat

import com.google.gson.JsonObject

/**
 * Build provider-specific request parameters.
 *
 * Baseline: stream is controlled by request.stream.
 * Provider-specific advanced controls are sent via providerOptions.
 */
object ProviderRequestOptionsBuilder {

    @JvmStatic
    fun buildProviderOptions(providerId: String?, options: SessionChatOptions?): JsonObject? {
        if (options == null || !options.thinking) return null
        val pid = providerId?.lowercase() ?: ""

        if (pid == "deepseek") {
            // DeepSeek reasoner behavior is model-driven; avoid injecting OpenAI-only reasoning knobs.
            return null
        }

        if (pid == "gemini" || pid == "google") {
            val google = JsonObject()
            val thinkingConfig = JsonObject()
            val budget = if (options.googleThinkingBudget > 0) options.googleThinkingBudget else 1024
            thinkingConfig.addProperty("thinkingBudget", budget)
            thinkingConfig.addProperty("includeThoughts", true)
            google.add("thinkingConfig", thinkingConfig)
            val root = JsonObject()
            root.add("google", google)
            return root
        }

        if (pid == "claude" || pid == "anthropic") {
            val claude = JsonObject()
            val thinking = JsonObject()
            thinking.addProperty("type", "enabled")
            thinking.addProperty("budgetTokens", 1024)
            claude.add("thinking", thinking)
            val root = JsonObject()
            root.add("claude", claude)
            return root
        }

        if (isLlamaProvider(pid)) {
            val openai = JsonObject()
            // Keep both styles for compatibility with different OpenAI-compatible gateways.
            openai.addProperty("reasoningEffort", "low")
            openai.addProperty("reasoning_effort", "low")
            val root = JsonObject()
            root.add("openai", openai)
            return root
        }

        // OpenAI-compatible providers: use medium reasoning effort hint.
        val openai = JsonObject()
        val root = JsonObject()
        root.add("openai", openai)
        return root
    }

    @JvmStatic
    fun buildReasoningConfig(providerId: String?, options: SessionChatOptions?): JsonObject? {
        if (options == null) return null
        val pid = providerId?.lowercase() ?: ""
        if (!isLlamaProvider(pid)) return null

        val reasoning = JsonObject()
        if (options.thinking) {
            // Lower budget typically shortens chain-of-thought generation time on llama.cpp.
            reasoning.addProperty("budget", 128)
        } else {
            // Explicitly request no reasoning for llama.cpp when thinking toggle is off.
            reasoning.addProperty("budget", 0)
        }
        // Keep reasoning out of normal answer content when server supports it.
        reasoning.addProperty("format", "hide")
        return reasoning
    }

    private fun isLlamaProvider(pid: String?): Boolean {
        if (pid.isNullOrEmpty()) return false
        return pid == "llama" || pid == "llamacpp" || pid == "llama.cpp" || pid == "llama-cpp"
    }
}
