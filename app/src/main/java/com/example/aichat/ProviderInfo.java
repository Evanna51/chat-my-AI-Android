package com.example.aichat;

import java.util.ArrayList;
import java.util.List;

/**
 * 参考 chatbox 的 Provider 结构
 * @see ../chatbox/src/shared/types.ts ProviderBaseInfo, ProviderSettings
 */
public class ProviderInfo {
    public String id;
    public String name;
    public String type = "openai"; // ModelProviderType.OpenAI = openai
    public boolean isCustom;

    public String apiHost;
    public String apiPath;
    public String apiKey;
    public List<ProviderModelInfo> models = new ArrayList<>();

    public ProviderInfo() {}

    public static ProviderInfo createCustom(String id, String name) {
        ProviderInfo p = new ProviderInfo();
        p.id = id;
        p.name = name;
        p.type = "openai";
        p.isCustom = true;
        p.apiHost = "";
        p.apiPath = "/chat/completions";
        p.apiKey = "";
        return p;
    }

    public static class ProviderModelInfo {
        public String modelId;
        public String nickname;

        public ProviderModelInfo() {}
        public ProviderModelInfo(String modelId) {
            this.modelId = modelId;
        }
    }
}
