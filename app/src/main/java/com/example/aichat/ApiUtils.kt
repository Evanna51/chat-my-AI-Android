package com.example.aichat

/**
 * 参考 chatbox src/shared/utils/llm_utils.ts normalizeOpenAIApiHostAndPath
 */
object ApiUtils {
    private const val DEFAULT_PATH = "/chat/completions"

    class HostAndPath(@JvmField var apiHost: String, @JvmField var apiPath: String)

    @JvmStatic
    fun normalizeOpenAIApiHostAndPath(apiHost: String?, apiPath: String?): HostAndPath {
        var host = apiHost?.trim()
        var path = apiPath?.trim()
        val defHost = "https://api.openai.com/v1"
        if (host.isNullOrEmpty()) {
            return HostAndPath(defHost, DEFAULT_PATH)
        }
        if (host.endsWith("/")) {
            host = host.substring(0, host.length - 1)
        }
        if (!path.isNullOrEmpty() && !path.startsWith("/")) {
            path = "/$path"
        }
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "https://$host"
        }
        if (host.endsWith(DEFAULT_PATH)) {
            host = host.replace(DEFAULT_PATH, "")
            path = DEFAULT_PATH
        }
        if (host.endsWith("://api.openai.com") || host.endsWith("://api.openai.com/v1")) {
            return HostAndPath(defHost, DEFAULT_PATH)
        }
        if (!host.endsWith("/v1") && path.isNullOrEmpty()) {
            host = "$host/v1"
            path = DEFAULT_PATH
        }
        if (path.isNullOrEmpty()) {
            path = DEFAULT_PATH
        }
        return HostAndPath(host, path)
    }

    /** 拼接完整 baseUrl，用于 Retrofit 等 */
    @JvmStatic
    fun toBaseUrl(apiHost: String?, apiPath: String?): String {
        val hp = normalizeOpenAIApiHostAndPath(apiHost, apiPath)
        var path = hp.apiPath
        if (path.startsWith("/")) path = path.substring(1)
        var host = hp.apiHost
        if (host.endsWith("/")) host = host.substring(0, host.length - 1)
        // Avoid duplicated version segment like /v1/v1/chat/completions
        if (host.endsWith("/v1") && path.startsWith("v1/")) {
            path = path.substring(3)
        }
        return "$host/${path.replaceFirst("^/".toRegex(), "")}"
    }

    /** 用于 GET /models 的 baseUrl，例如 https://api.openai.com/v1 */
    @JvmStatic
    fun toModelsBaseUrl(apiHost: String?, apiPath: String?): String {
        val hp = normalizeOpenAIApiHostAndPath(apiHost, apiPath)
        var host = hp.apiHost
        if (host.endsWith("/")) host = host.substring(0, host.length - 1)
        return host
    }
}
