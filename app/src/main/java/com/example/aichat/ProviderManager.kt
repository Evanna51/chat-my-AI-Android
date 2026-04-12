package com.example.aichat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ProviderManager(context: Context) {

    private val context: Context = context.applicationContext

    class ProviderSettings {
        @JvmField var apiHost: String? = null
        @JvmField var apiPath: String? = null
        @JvmField var apiKey: String? = null
        @JvmField var models: List<ProviderInfo.ProviderModelInfo>? = null
    }

    private fun getEnabledIds(): MutableSet<String> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENABLED_IDS, "[]")
        val list = GSON.fromJson<List<*>>(json, List::class.java)
        return if (list != null) HashSet(list.filterIsInstance<String>()) else HashSet()
    }

    private fun saveEnabledIds(ids: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENABLED_IDS, GSON.toJson(ArrayList(ids)))
            .apply()
    }

    fun getProviderSettingsMap(): MutableMap<String, ProviderSettings> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROVIDER_SETTINGS, "{}")
        val map: Map<String, ProviderSettings>? = GSON.fromJson(json, SETTINGS_MAP_TYPE)
        val safeMap: MutableMap<String, ProviderSettings> = if (map != null) HashMap(map) else HashMap()
        if (normalizeSettingsMapInPlace(safeMap)) {
            saveProviderSettingsMap(safeMap)
        }
        return safeMap
    }

    private fun saveProviderSettingsMap(map: Map<String, ProviderSettings>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROVIDER_SETTINGS, GSON.toJson(map))
            .apply()
    }

    private fun normalizeSettingsMapInPlace(map: MutableMap<String, ProviderSettings>): Boolean {
        var changed = false
        for ((_, s) in map) {
            if (s == null) continue
            val oldHost = s.apiHost ?: ""
            val oldPath = s.apiPath ?: ""

            if (oldHost.isEmpty()) {
                if ("/v1/chat/completions" == oldPath) {
                    s.apiPath = "/chat/completions"
                    changed = true
                }
                continue
            }

            val hp = ApiUtils.normalizeOpenAIApiHostAndPath(oldHost, oldPath)
            val newHost = if (hp?.apiHost != null) hp.apiHost else oldHost
            val newPath = if (hp?.apiPath != null) hp.apiPath else oldPath
            if (newHost != oldHost || newPath != oldPath) {
                s.apiHost = newHost
                s.apiPath = newPath
                changed = true
            }
        }
        return changed
    }

    fun getAllProviders(): List<ProviderInfo> {
        val enabled = getEnabledIds()
        val settings = getProviderSettingsMap()
        val result = ArrayList<ProviderInfo>()
        for (id in getOrderedIds()) {
            if (!enabled.contains(id)) continue
            val catalog = ProviderCatalog.get(id) ?: continue
            val s = settings[id]
            val p = toProviderInfo(catalog, s)
            result.add(p)
        }
        return result
    }

    private fun getOrderedIds(): List<String> {
        val order = ArrayList<String>()
        for (item in ProviderCatalog.getAll()) {
            order.add(item.id)
        }
        return order
    }

    private fun toProviderInfo(catalog: ProviderCatalog.CatalogItem, s: ProviderSettings?): ProviderInfo {
        val p = ProviderInfo()
        p.id = catalog.id
        p.name = catalog.name
        p.type = "openai"
        p.isCustom = "custom" == catalog.id
        val sApiHost = s?.apiHost
        val sApiPath = s?.apiPath
        val sApiKey = s?.apiKey
        val sModels = s?.models
        p.apiHost = if (!sApiHost.isNullOrEmpty()) sApiHost else catalog.apiHost
        p.apiPath = if (!sApiPath.isNullOrEmpty()) sApiPath else catalog.apiPath
        if (p.apiPath.isNullOrEmpty()) p.apiPath = "/chat/completions"
        p.apiKey = sApiKey ?: ""
        p.models = if (sModels != null) sModels.toMutableList() else mutableListOf()
        return p
    }

    /** 添加预设厂商 */
    fun addPresetProvider(providerId: String) {
        val enabled = getEnabledIds()
        enabled.add(providerId)
        saveEnabledIds(enabled)
        val catalog = ProviderCatalog.get(providerId)
        if (catalog != null) {
            val map = getProviderSettingsMap()
            if (!map.containsKey(providerId)) {
                val s = ProviderSettings()
                s.apiHost = catalog.apiHost
                s.apiPath = catalog.apiPath
                s.apiKey = ""
                s.models = ArrayList()
                map[providerId] = s
                saveProviderSettingsMap(map)
            }
        }
    }

    /** 是否已添加 */
    fun isEnabled(providerId: String): Boolean {
        return getEnabledIds().contains(providerId)
    }

    /** 删除厂商 */
    fun removeProvider(providerId: String) {
        val enabled = getEnabledIds()
        enabled.remove(providerId)
        saveEnabledIds(enabled)
        val settings = getProviderSettingsMap()
        settings.remove(providerId)
        saveProviderSettingsMap(settings)
    }

    fun updateProviderSettings(providerId: String, s: ProviderSettings) {
        val map = getProviderSettingsMap()
        map[providerId] = s
        saveProviderSettingsMap(map)
    }

    fun getProvider(providerId: String): ProviderInfo? {
        for (p in getAllProviders()) {
            if (providerId == p.id) return p
        }
        return null
    }

    companion object {
        private const val PREFS = "aichat_providers"
        private const val KEY_ENABLED_IDS = "enabled_provider_ids"
        private const val KEY_PROVIDER_SETTINGS = "provider_settings"
        private val GSON = Gson()
        private val STRING_SET_TYPE = object : TypeToken<Set<String>>() {}.type
        private val SETTINGS_MAP_TYPE = object : TypeToken<Map<String, ProviderSettings>>() {}.type
    }
}
