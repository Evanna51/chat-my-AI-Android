package com.example.aichat;

import com.google.gson.JsonObject;

/**
 * Build provider-specific request parameters.
 *
 * Baseline: stream is controlled by request.stream.
 * Provider-specific advanced controls are sent via providerOptions.
 */
public final class ProviderRequestOptionsBuilder {
    private ProviderRequestOptionsBuilder() {}

    public static JsonObject buildProviderOptions(String providerId, SessionChatOptions options) {
        if (options == null || !options.thinking) return null;
        String pid = providerId != null ? providerId.toLowerCase() : "";

        if ("gemini".equals(pid) || "google".equals(pid)) {
            JsonObject google = new JsonObject();
            JsonObject thinkingConfig = new JsonObject();
            int budget = options.googleThinkingBudget > 0 ? options.googleThinkingBudget : 1024;
            thinkingConfig.addProperty("thinkingBudget", budget);
            thinkingConfig.addProperty("includeThoughts", true);
            google.add("thinkingConfig", thinkingConfig);
            JsonObject root = new JsonObject();
            root.add("google", google);
            return root;
        }

        if ("claude".equals(pid) || "anthropic".equals(pid)) {
            JsonObject claude = new JsonObject();
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "enabled");
            thinking.addProperty("budgetTokens", 1024);
            claude.add("thinking", thinking);
            JsonObject root = new JsonObject();
            root.add("claude", claude);
            return root;
        }

        if (isLlamaProvider(pid)) {
            JsonObject openai = new JsonObject();
            // Keep both styles for compatibility with different OpenAI-compatible gateways.
            openai.addProperty("reasoningEffort", "low");
            openai.addProperty("reasoning_effort", "low");
            JsonObject root = new JsonObject();
            root.add("openai", openai);
            return root;
        }

        // OpenAI-compatible providers: use medium reasoning effort hint.
        JsonObject openai = new JsonObject();
        openai.addProperty("reasoningEffort", "medium");
        JsonObject root = new JsonObject();
        root.add("openai", openai);
        return root;
    }

    public static JsonObject buildReasoningConfig(String providerId, SessionChatOptions options) {
        if (options == null) return null;
        String pid = providerId != null ? providerId.toLowerCase() : "";
        if (!isLlamaProvider(pid)) return null;

        JsonObject reasoning = new JsonObject();
        if (options.thinking) {
            // Lower budget typically shortens chain-of-thought generation time on llama.cpp.
            reasoning.addProperty("budget", 128);
        } else {
            // Explicitly request no reasoning for llama.cpp when thinking toggle is off.
            reasoning.addProperty("budget", 0);
        }
        // Keep reasoning out of normal answer content when server supports it.
        reasoning.addProperty("format", "hide");
        return reasoning;
    }

    private static boolean isLlamaProvider(String pid) {
        if (pid == null || pid.isEmpty()) return false;
        return "llama".equals(pid)
                || "llamacpp".equals(pid)
                || "llama.cpp".equals(pid)
                || "llama-cpp".equals(pid);
    }
}
