package com.example.aichat;

/**
 * 参考 chatbox src/shared/utils/llm_utils.ts normalizeOpenAIApiHostAndPath
 */
public final class ApiUtils {
    private static final String DEFAULT_PATH = "/chat/completions";

    public static class HostAndPath {
        public String apiHost;
        public String apiPath;

        public HostAndPath(String apiHost, String apiPath) {
            this.apiHost = apiHost;
            this.apiPath = apiPath;
        }
    }

    public static HostAndPath normalizeOpenAIApiHostAndPath(String apiHost, String apiPath) {
        if (apiHost != null) apiHost = apiHost.trim();
        if (apiPath != null) apiPath = apiPath.trim();
        String defHost = "https://api.openai.com/v1";
        if (apiHost == null || apiHost.isEmpty()) {
            return new HostAndPath(defHost, DEFAULT_PATH);
        }
        if (apiHost.endsWith("/")) {
            apiHost = apiHost.substring(0, apiHost.length() - 1);
        }
        if (apiPath != null && !apiPath.isEmpty() && !apiPath.startsWith("/")) {
            apiPath = "/" + apiPath;
        }
        if (!apiHost.startsWith("http://") && !apiHost.startsWith("https://")) {
            apiHost = "https://" + apiHost;
        }
        if (apiHost.endsWith(DEFAULT_PATH)) {
            apiHost = apiHost.replace(DEFAULT_PATH, "");
            apiPath = DEFAULT_PATH;
        }
        if (apiHost.endsWith("://api.openai.com") || apiHost.endsWith("://api.openai.com/v1")) {
            return new HostAndPath(defHost, DEFAULT_PATH);
        }
        if (!apiHost.endsWith("/v1") && (apiPath == null || apiPath.isEmpty())) {
            apiHost = apiHost + "/v1";
            apiPath = DEFAULT_PATH;
        }
        if (apiPath == null || apiPath.isEmpty()) {
            apiPath = DEFAULT_PATH;
        }
        return new HostAndPath(apiHost, apiPath);
    }

    /** 拼接完整 baseUrl，用于 Retrofit 等 */
    public static String toBaseUrl(String apiHost, String apiPath) {
        HostAndPath hp = normalizeOpenAIApiHostAndPath(apiHost, apiPath);
        String path = hp.apiPath != null ? hp.apiPath : "";
        if (path.startsWith("/")) path = path.substring(1);
        String host = hp.apiHost;
        if (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        // Avoid duplicated version segment like /v1/v1/chat/completions
        if (host.endsWith("/v1") && path.startsWith("v1/")) {
            path = path.substring(3);
        }
        return host + "/" + path.replaceFirst("^/", "");
    }

    /** 用于 GET /models 的 baseUrl，例如 https://api.openai.com/v1 */
    public static String toModelsBaseUrl(String apiHost, String apiPath) {
        HostAndPath hp = normalizeOpenAIApiHostAndPath(apiHost, apiPath);
        String host = hp.apiHost;
        if (host == null) return "https://api.openai.com/v1";
        if (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        return host;
    }
}
