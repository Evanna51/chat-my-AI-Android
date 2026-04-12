package com.example.aichat

import android.content.Context

/**
 * 已配置的模型：厂商已设置 API Key（或本地厂商），且用户已添加该模型。
 * 用于模型配置选择器等。
 */
class ConfiguredModelPicker {

    class Option {
        @JvmField var providerId: String? = null
        @JvmField var providerName: String? = null
        @JvmField var modelId: String? = null
        @JvmField var displayName: String? = null  // nickname or modelId

        /** 存储格式，用于 ConfigManager */
        fun getStorageKey(): String = "$providerId:$modelId"

        companion object {
            @JvmStatic
            fun fromStorageKey(key: String?, ctx: Context): Option? {
                if (key == null || !key.contains(":")) return null
                val i = key.indexOf(':')
                val pid = key.substring(0, i)
                val mid = key.substring(i + 1)
                val pm = ProviderManager(ctx)
                val p = pm.getProvider(pid) ?: return null
                if (p.models == null) return null
                for (m in p.models) {
                    if (m != null && mid == m.modelId) {
                        val o = Option()
                        o.providerId = pid
                        o.providerName = p.name
                        o.modelId = mid
                        o.displayName = if (!m.nickname.isNullOrEmpty()) m.nickname else mid
                        return o
                    }
                }
                val o = Option()
                o.providerId = pid
                o.providerName = p.name
                o.modelId = mid
                o.displayName = mid
                return o
            }
        }
    }

    companion object {
        /** 获取所有可选的已配置模型 */
        @JvmStatic
        fun getConfiguredModels(ctx: Context): List<Option> {
            val pm = ProviderManager(ctx)
            val providers = pm.getAllProviders()
            val result = ArrayList<Option>()
            for (p in providers) {
                val cat = ProviderCatalog.get(p.id)
                val needsKey = cat == null || cat.needsKey
                val hasHost = !p.apiHost.isNullOrEmpty()
                val hasKey = !needsKey || !p.apiKey.isNullOrEmpty()
                if (!hasHost || !hasKey) continue
                if (p.models == null || p.models.isEmpty()) continue
                for (m in p.models) {
                    if (m.modelId == null) continue
                    val o = Option()
                    o.providerId = p.id
                    o.providerName = p.name
                    o.modelId = m.modelId
                    o.displayName = if (!m.nickname.isNullOrEmpty()) m.nickname else m.modelId
                    result.add(o)
                }
            }
            return result
        }
    }
}
