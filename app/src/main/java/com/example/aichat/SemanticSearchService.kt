package com.example.aichat

import android.content.Context
import com.google.gson.JsonParser

class SemanticSearchService(context: Context) {
    private val ctx = context.applicationContext
    private val db = AppDatabase.getInstance(ctx)
    private val modelConfig = ModelConfig(ctx)
    private val providerManager = ProviderManager(ctx)

    data class Resolved(val provider: ProviderInfo, val modelId: String)
    data class SemanticHit(val sessionId: String, val score: Float, val sampleMessageContent: String)

    fun resolveEmbeddingModel(): Resolved? {
        val key = modelConfig.getEmbeddingPreset()
        if (key.isNullOrBlank() || !key.contains(':')) return null
        val parts = key.split(":", limit = 2)
        val providerId = parts[0]
        val modelId = parts[1]
        val provider = providerManager.getProvider(providerId) ?: return null
        if (provider.apiKey.isNullOrBlank() || provider.apiHost.isNullOrBlank()) return null
        return Resolved(provider, modelId)
    }

    fun embedText(text: String): FloatArray? {
        val r = resolveEmbeddingModel() ?: return null
        return EmbeddingsApi.embed(r.provider.apiHost, r.provider.apiPath, r.provider.apiKey, r.modelId, text)
    }

    fun indexPendingMessages(maxBatch: Int = 50): Int {
        val r = resolveEmbeddingModel() ?: return 0
        val pending = db.messageDao().getMessagesNeedingEmbedding(maxBatch) ?: return 0
        var done = 0
        for (m in pending) {
            if (m.content.isBlank()) continue
            val v = EmbeddingsApi.embed(r.provider.apiHost, r.provider.apiPath, r.provider.apiKey, r.modelId, m.content) ?: continue
            db.messageDao().updateEmbedding(m.id, serialize(v))
            done++
        }
        return done
    }

    fun searchSessions(query: String, topN: Int = 10): List<SemanticHit> {
        val q = embedText(query) ?: return emptyList()
        val all = db.messageDao().getAllWithEmbedding() ?: return emptyList()
        val bestPerSession = HashMap<String, SemanticHit>()
        for (m in all) {
            val v = deserialize(m.embedding) ?: continue
            val score = cosine(q, v)
            val cur = bestPerSession[m.sessionId]
            if (cur == null || score > cur.score) {
                bestPerSession[m.sessionId] = SemanticHit(m.sessionId, score, m.content)
            }
        }
        return bestPerSession.values.sortedByDescending { it.score }.take(topN)
    }

    private fun serialize(v: FloatArray): String {
        val sb = StringBuilder("[")
        for (i in v.indices) {
            if (i > 0) sb.append(',')
            sb.append(v[i])
        }
        sb.append(']')
        return sb.toString()
    }

    private fun deserialize(s: String): FloatArray? {
        if (s.isBlank()) return null
        return try {
            val arr = JsonParser().parse(s).asJsonArray
            FloatArray(arr.size()) { i -> arr.get(i).asFloat }
        } catch (e: Exception) { null }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (Math.sqrt(na) * Math.sqrt(nb))).toFloat()
    }
}
