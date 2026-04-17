package com.example.aichat

import android.content.Context
import android.content.SharedPreferences

class TTSConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** true = HTTP API mode (supports voice cloning), false = SDK mode */
    fun isHttpApiMode(): Boolean = prefs.getBoolean(KEY_USE_HTTP_API, true)

    // --- SDK mode fields ---

    fun getAppId(): String = prefs.getString(KEY_APP_ID, "") ?: ""

    fun getAccessToken(): String = prefs.getString(KEY_ACCESS_TOKEN, "") ?: ""

    // --- HTTP API mode fields ---

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""

    fun getEncoding(): String = prefs.getString(KEY_ENCODING, DEFAULT_ENCODING) ?: DEFAULT_ENCODING

    /** X-Api-Resource-Id: seed-icl-2.0 / seed-icl-1.0 / seed-tts-2.0 / seed-tts-1.0 */
    fun getResourceId(): String = prefs.getString(KEY_RESOURCE_ID, DEFAULT_RESOURCE_ID) ?: DEFAULT_RESOURCE_ID

    // --- Shared fields ---

    fun getCluster(): String = prefs.getString(KEY_CLUSTER, DEFAULT_CLUSTER) ?: DEFAULT_CLUSTER

    fun getVoiceType(): String = prefs.getString(KEY_VOICE_TYPE, DEFAULT_VOICE_TYPE) ?: DEFAULT_VOICE_TYPE

    fun getSpeedRatio(): Float = prefs.getFloat(KEY_SPEED_RATIO, DEFAULT_SPEED_RATIO)

    fun getVolumeRatio(): Float = prefs.getFloat(KEY_VOLUME_RATIO, DEFAULT_VOLUME_RATIO)

    fun saveAll(
        enabled: Boolean,
        useHttpApi: Boolean,
        appId: String?,
        accessToken: String?,
        apiKey: String?,
        encoding: String?,
        resourceId: String?,
        cluster: String?,
        voiceType: String?,
        speedRatio: Float,
        volumeRatio: Float
    ) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putBoolean(KEY_USE_HTTP_API, useHttpApi)
            .putString(KEY_APP_ID, appId?.trim() ?: "")
            .putString(KEY_ACCESS_TOKEN, accessToken?.trim() ?: "")
            .putString(KEY_API_KEY, apiKey?.trim() ?: "")
            .putString(KEY_ENCODING, encoding?.trim()?.ifEmpty { DEFAULT_ENCODING } ?: DEFAULT_ENCODING)
            .putString(KEY_RESOURCE_ID, resourceId?.trim()?.ifEmpty { DEFAULT_RESOURCE_ID } ?: DEFAULT_RESOURCE_ID)
            .putString(KEY_CLUSTER, cluster?.trim()?.ifEmpty { DEFAULT_CLUSTER } ?: DEFAULT_CLUSTER)
            .putString(KEY_VOICE_TYPE, voiceType?.trim()?.ifEmpty { DEFAULT_VOICE_TYPE } ?: DEFAULT_VOICE_TYPE)
            .putFloat(KEY_SPEED_RATIO, speedRatio.coerceIn(0.5f, 2.0f))
            .putFloat(KEY_VOLUME_RATIO, volumeRatio.coerceIn(0.5f, 2.0f))
            .apply()
    }

    companion object {
        private const val PREFS = "aichat_tts_config"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_USE_HTTP_API = "use_http_api"
        private const val KEY_APP_ID = "app_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ENCODING = "encoding"
        private const val KEY_CLUSTER = "cluster"
        private const val KEY_VOICE_TYPE = "voice_type"
        private const val KEY_RESOURCE_ID = "resource_id"
        private const val KEY_SPEED_RATIO = "speed_ratio"
        private const val KEY_VOLUME_RATIO = "volume_ratio"

        const val DEFAULT_CLUSTER = "volcano_tts"
        const val DEFAULT_CLUSTER_ICL = "volcano_icl"
        const val DEFAULT_VOICE_TYPE = "zh_female_shuangkuaisisi_moon_bigtts"
        const val DEFAULT_RESOURCE_ID = "seed-icl-1.0"
        const val DEFAULT_ENCODING = "mp3"
        const val DEFAULT_SPEED_RATIO = 1.0f
        const val DEFAULT_VOLUME_RATIO = 1.0f
    }
}
