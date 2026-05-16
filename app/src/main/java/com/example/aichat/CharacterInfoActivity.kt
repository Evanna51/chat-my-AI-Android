package com.example.aichat

import android.content.res.Resources
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.aichat.sync.CharacterBootstrapStore
import com.example.aichat.sync.EffectivePromptStore
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 展示当前会话绑定的角色最近一次 /api/character/context 响应内容.
 * 和 /api/chat/context的内容
 * 从 [CharacterBootstrapStore] 内存缓存读取, 无网络请求.
 *
 * Phase 2 cleanup（2026-05-10）：从单一 raw JSON 文本展示，改为分段格式化展示：
 *   - 角色档案、当下心境、角色身份、约束、关系动力学、叙事记忆 — 友好可读
 *   - 完整 system prompt  mergedSystem — monospace 块
 */
class CharacterInfoActivity : ThemedActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_character_info)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val container = findViewById<LinearLayout>(R.id.contentContainer)
        val emptyView = findViewById<TextView>(R.id.textEmpty)
        val fetchedAtView = findViewById<TextView>(R.id.textFetchedAt)

        val sid = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val assistantId = if (sid.isNotEmpty())
            SessionAssistantBindingStore(this).getAssistantId(sid) else ""
        val cache = CharacterBootstrapStore.getInstance(this).getCached(assistantId)

        if (cache == null || cache.rawJson.isBlank()) {
            emptyView.visibility = View.VISIBLE
            container.visibility = View.GONE
            fetchedAtView.visibility = View.GONE
            return
        }

        fetchedAtView.text = getString(R.string.character_info_fetched_at,
            timeFmt.format(Date(cache.fetchedAtMs)))

        val effectivePrompt = EffectivePromptStore.get(assistantId)
        renderSections(container, cache.rawJson, effectivePrompt)
    }

    // ── 渲染入口 ───────────────────────────────────────────────────────

    private fun renderSections(
        container: LinearLayout,
        rawJson: String,
        effectivePrompt: EffectivePromptStore.Snapshot?,
    ) {
        container.removeAllViews()

        val root = try {
            JsonParser().parse(rawJson).asJsonObject
        } catch (_: Exception) {
            addSection(container, "原始 JSON（解析失败）", rawJson, monospace = true)
            return
        }

        // 1. 角色档案
        renderProfileSection(container, root)

        // 2. 当下心境（emotion + assistantPrefill 独白）
        renderEmotionSection(container, root)

        // 3. 角色身份（identity 字段，V_NEW_LEAN 精简版）
        renderIdentitySection(container, root)

        // 4. 约束（hard / soft / avoidance / triggering）
        renderConstraintsSection(container, root)

        // 5. 关系动力学（12 维 dynamics）
        renderDynamicsSection(container, root)

        // 6. 叙事记忆（reflection / episodes / topics）
        renderNarrativeSection(container, root)

        // 7. 实际下发 system prompt（EffectivePromptStore 记录，每次发消息后更新）
        val promptText = effectivePrompt?.systemPrompt.orEmpty()
        val meta = buildString {
            if (effectivePrompt != null) {
                append("source: ${effectivePrompt.source}")
                if (!effectivePrompt.routerSummary.isNullOrBlank())
                    append("  |  ${effectivePrompt.routerSummary}")
            }
        }
        if (meta.isNotBlank()) {
            addSection(container, "ℹ️ prompt 来源", meta, small = true)
        }
        addSection(container,
            "🛠 实际下发 system prompt",
            promptText.ifBlank { "（暂无记录 — 在聊天页发一条消息后刷新此页）" },
            monospace = promptText.isNotBlank(),
            small = true)
    }

    // ── section: 角色档案 ─────────────────────────────────────────────

    private fun renderProfileSection(parent: LinearLayout, root: JsonObject) {
        // /api/character/context 返回的 ctx 不含独立 profile 字段，但 identity payload
        // 中带了 characterName。background 走另一路（client 别处有 profile metadata）。
        val identity = root.getAsJsonObjectOrNull("identity")
        val name = identity?.let { readStr(it, "characterName") }.orEmpty()
        val pronouns = identity?.let { readStr(it, "pronouns") }.orEmpty()

        val sb = StringBuilder()
        if (name.isNotEmpty()) appendKv(sb, "姓名", name)
        if (pronouns.isNotEmpty()) appendKv(sb, "代词", pronouns)
        if (sb.isEmpty()) return
        addSection(parent, "📋 角色档案", sb.toString().trimEnd())
    }

    // ── section: 当下心境 ─────────────────────────────────────────────

    private fun renderEmotionSection(parent: LinearLayout, root: JsonObject) {
        val emotion = root.getAsJsonObjectOrNull("emotion")
        val sb = StringBuilder()
        emotion?.getAsJsonObjectOrNull("current")?.let { cur ->
            val zh = readStr(cur, "zh").ifEmpty { readStr(cur, "id") }
            val intensity = readDouble(cur, "intensity")
            val valence = readDouble(cur, "valence")
            val arousal = readDouble(cur, "arousal")
            if (zh.isNotEmpty()) {
                sb.appendLine("当前情绪：$zh（强度 ${formatFloat(intensity)}）")
                if (intensity >= 0.3) {
                    sb.appendLine("  ・valence ${formatFloat(valence)} · arousal ${formatFloat(arousal)}")
                }
            }
        }
        emotion?.getAsJsonObjectOrNull("suppressed")?.let { sup ->
            if (!sup.entrySet().isEmpty()) {
                val zh = readStr(sup, "zh")
                val intensity = readDouble(sup, "intensity")
                if (zh.isNotEmpty() && intensity > 0.0) {
                    sb.appendLine("压抑情绪：$zh（强度 ${formatFloat(intensity)}）")
                }
            }
        }
        if (sb.isNotEmpty()) {
            addSection(parent, "💭 当下心境", sb.toString().trimEnd())
        }

        // assistantPrefill 独白片段单独 section
        val prefill = readStr(root, "assistantPrefill")
        if (prefill.isNotBlank()) {
            addSection(parent, "  ↳ 内心独白（[此刻] 段）", prefill, italic = true)
        }
    }

    // ── section: 角色身份 ─────────────────────────────────────────────

    private fun renderIdentitySection(parent: LinearLayout, root: JsonObject) {
        val id = root.getAsJsonObjectOrNull("identity") ?: return
        val sb = StringBuilder()
        appendKvIfNotEmpty(sb, "说话风格", readStr(id, "speakingStyle"))
        appendKvIfNotEmpty(sb, "世界观", readStr(id, "worldview"))
        appendKvIfNotEmpty(sb, "人格特质", readStrArray(id, "personalityTraits").joinToString("、"))
        appendKvIfNotEmpty(sb, "依恋类型", readStr(id, "attachmentStyle"))
        appendKvIfNotEmpty(sb, "核心价值", readStrArray(id, "values").joinToString("、"))
        // care_languages: { give: [...], receive: [...] }
        id.getAsJsonObjectOrNull("careLanguages")?.let { cl ->
            val give = readStrArray(cl, "give")
            val receive = readStrArray(cl, "receive")
            if (give.isNotEmpty()) appendKv(sb, "习惯关心方式", give.joinToString("、"))
            if (receive.isNotEmpty()) appendKv(sb, "易被打动方式", receive.joinToString("、"))
        }
        // skills: 可能是 string[] 或 [{name, examples}]
        val skillNames = readSkillNames(id)
        if (skillNames.isNotEmpty()) {
            appendKv(sb, "表达招式", skillNames.joinToString("、"))
        }
        if (sb.isEmpty()) return
        addSection(parent, "🎭 角色身份", sb.toString().trimEnd())
    }

    // ── section: 约束 ─────────────────────────────────────────────────

    private fun renderConstraintsSection(parent: LinearLayout, root: JsonObject) {
        val id = root.getAsJsonObjectOrNull("identity") ?: return
        val sb = StringBuilder()
        appendBoundaries(sb, "🚫 绝对边界", readStrArray(id, "hardBoundaries"))
        appendBoundaries(sb, "  软边界", readStrArray(id, "softBoundaries"))
        appendBoundaries(sb, "  回避话题", readStrArray(id, "avoidanceTopics"))
        appendBoundaries(sb, "  触发话题", readStrArray(id, "triggeringTopics"))
        if (sb.isEmpty()) return
        addSection(parent, "🚧 约束", sb.toString().trimEnd())
    }

    private fun appendBoundaries(sb: StringBuilder, label: String, items: List<String>) {
        if (items.isEmpty()) return
        sb.append(label).append("：\n")
        items.forEach { sb.append("  ・").append(it).append('\n') }
    }

    // ── section: 关系动力学 ────────────────────────────────────────────

    private fun renderDynamicsSection(parent: LinearLayout, root: JsonObject) {
        val dyn = root.getAsJsonObjectOrNull("relationshipDynamics") ?: return
        // 12 维：高亮关键 4 维 + 其它分类罗列
        val sb = StringBuilder()
        // 关系基线（健康向高）
        sb.appendLine("✦ 关系基线")
        appendDynamicLine(sb, "信任 trust", readDouble(dyn, "trust"), highIsHealthy = true)
        appendDynamicLine(sb, "亲近 emotionalCloseness", readDouble(dyn, "emotionalCloseness"), highIsHealthy = true)
        appendDynamicLine(sb, "情感安全 emotionalSafety", readDouble(dyn, "emotionalSafety"), highIsHealthy = true)
        appendDynamicLine(sb, "依恋 attachment", readDouble(dyn, "attachment"), highIsHealthy = true)
        sb.appendLine("\n✦ 张力 / 阻抗（健康向低）")
        appendDynamicLine(sb, "紧张 tension", readDouble(dyn, "tension"), highIsHealthy = false)
        appendDynamicLine(sb, "未解冲突 unresolvedConflict", readDouble(dyn, "unresolvedConflict"), highIsHealthy = false)
        appendDynamicLine(sb, "怨气 resentment", readDouble(dyn, "resentment"), highIsHealthy = false)
        appendDynamicLine(sb, "被弃恐惧 abandonmentFear", readDouble(dyn, "abandonmentFear"), highIsHealthy = false)
        appendDynamicLine(sb, "社交距离 socialDistance", readDouble(dyn, "socialDistance"), highIsHealthy = false)
        sb.appendLine("\n✦ 互动")
        appendDynamicLine(sb, "感激 gratitude", readDouble(dyn, "gratitude"), highIsHealthy = true)
        appendDynamicLine(sb, "互惠平衡 reciprocityBalance", readDouble(dyn, "reciprocityBalance"), highIsHealthy = true)
        appendDynamicLine(sb, "依赖 dependency", readDouble(dyn, "dependency"), highIsHealthy = false)

        addSection(parent, "📊 关系动力学", sb.toString().trimEnd())
    }

    private fun appendDynamicLine(sb: StringBuilder, label: String, v: Double, highIsHealthy: Boolean) {
        // 简易 sparkline：根据值生成进度条
        val capped = v.coerceIn(0.0, 1.0)
        val bars = (capped * 10).toInt().coerceAtLeast(0)
        val empty = 10 - bars
        val bar = "█".repeat(bars) + "░".repeat(empty)
        val healthIcon = when {
            highIsHealthy && v >= 0.5 -> "🟢"
            highIsHealthy && v < 0.3 -> "🔴"
            !highIsHealthy && v >= 0.5 -> "🔴"
            !highIsHealthy && v < 0.3 -> "🟢"
            else -> "🟡"
        }
        sb.append("  $healthIcon $label: $bar  ").append(formatFloat(v)).append('\n')
    }

    // ── section: 叙事记忆 ────────────────────────────────────────────

    private fun renderNarrativeSection(parent: LinearLayout, root: JsonObject) {
        val sb = StringBuilder()

        // 反思（latestReflection）
        root.getAsJsonObjectOrNull("latestReflection")?.let { refl ->
            val summary = readStr(refl, "summary")
            if (summary.isNotBlank()) {
                val direction = readStr(refl, "relationshipDirection")
                sb.append("📔 最近反思")
                if (direction.isNotEmpty()) sb.append("（方向：$direction）")
                sb.append("：\n").append(summary).append("\n\n")
            }
        }

        // 活跃叙事段（recentEpisodes，前 3 条）
        val episodes = root.getAsJsonArrayOrNull("recentEpisodes")
        if (episodes != null && episodes.size() > 0) {
            sb.append("📖 活跃叙事段：\n")
            episodes.take(3).forEach { e ->
                val obj = e.asJsonObjectOrNull() ?: return@forEach
                val title = readStr(obj, "title")
                val summary = readStr(obj, "summary")
                val tone = readStr(obj, "emotionalTone")
                if (title.isNotEmpty()) {
                    sb.append("  ・$title")
                    if (tone.isNotEmpty()) sb.append("  [$tone]")
                    sb.append('\n')
                    if (summary.isNotEmpty()) sb.append("    $summary\n")
                }
            }
            sb.append('\n')
        }

        // 长期话题（activeTopics，前 5 条）
        val topics = root.getAsJsonArrayOrNull("activeTopics")
        if (topics != null && topics.size() > 0) {
            sb.append("🏷 长期话题：\n")
            topics.take(5).forEach { t ->
                val obj = t.asJsonObjectOrNull() ?: return@forEach
                val topic = readStr(obj, "topic")
                val status = readStr(obj, "status")
                val mention = readInt(obj, "mentionCount")
                if (topic.isNotEmpty()) {
                    sb.append("  ・$topic")
                    if (status.isNotEmpty()) sb.append("（$status）")
                    if (mention > 0) sb.append(" · ${mention} 次")
                    sb.append('\n')
                }
            }
            sb.append('\n')
        }

        // 选择性注意命中（salientPhrase）
        root.getAsJsonObjectOrNull("salientPhrase")?.let { sp ->
            val phrase = readStr(sp, "phrase")
            val trigger = readStr(sp, "triggerSource")
            if (phrase.isNotEmpty()) {
                sb.append("🎯 选择性注意命中：「$phrase」")
                if (trigger.isNotEmpty()) sb.append("（$trigger）")
            }
        }

        if (sb.isNotEmpty()) {
            addSection(parent, "📚 叙事记忆", sb.toString().trimEnd())
        }
    }

    // ── 通用：添加 section ─────────────────────────────────────────────

    private fun addSection(
        parent: LinearLayout,
        title: String,
        content: CharSequence,
        monospace: Boolean = false,
        small: Boolean = false,
        italic: Boolean = false,
    ) {
        // section title (group label, iOS-style)
        val titleView = TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(this@CharacterInfoActivity, R.color.ios_section_label))
            setPadding(dp(20), dp(20), dp(20), dp(8))
        }
        parent.addView(titleView)

        // card with content
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(
                this@CharacterInfoActivity, R.color.ios_cell_bg))
            strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(16), 0, dp(16), 0)
            }
        }
        val tv = TextView(this).apply {
            text = content
            setTextSize(TypedValue.COMPLEX_UNIT_SP,
                if (small) 11f else if (monospace) 11f else 14f)
            setLineSpacing(0f, if (monospace) 1.15f else 1.3f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextIsSelectable(true)
            if (monospace) {
                typeface = Typeface.MONOSPACE
            } else if (italic) {
                setTypeface(typeface, Typeface.ITALIC)
            }
            gravity = Gravity.START
        }
        card.addView(tv)
        parent.addView(card)
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun dp(v: Int): Int =
        (v * Resources.getSystem().displayMetrics.density).toInt()

    private fun appendKv(sb: StringBuilder, key: String, value: String) {
        sb.append(key).append("：").append(value).append('\n')
    }

    private fun appendKvIfNotEmpty(sb: StringBuilder, key: String, value: String) {
        if (value.isNotBlank()) appendKv(sb, key, value)
    }

    private fun formatFloat(v: Double): String =
        String.format(Locale.US, "%.2f", v)

    private fun readSkillNames(identity: JsonObject): List<String> {
        val arr = identity.getAsJsonArrayOrNull("skills") ?: return emptyList()
        val out = ArrayList<String>()
        for (e in arr) {
            if (e.isJsonNull) continue
            if (e.isJsonPrimitive && e.asJsonPrimitive.isString) {
                out.add(e.asString)
            } else if (e.isJsonObject) {
                val n = readStr(e.asJsonObject, "name")
                if (n.isNotBlank()) out.add(n)
            }
        }
        return out
    }

    // ── JSON 取值小工具 ──────────────────────────────────────────────

    private fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? {
        val e = this.get(key) ?: return null
        return if (e.isJsonNull || !e.isJsonObject) null else e.asJsonObject
    }

    private fun JsonObject.getAsJsonArrayOrNull(key: String) =
        this.get(key)?.takeIf { !it.isJsonNull && it.isJsonArray }?.asJsonArray

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        if (this.isJsonNull || !this.isJsonObject) null else this.asJsonObject

    private fun readStr(obj: JsonObject, key: String): String {
        val e = obj.get(key) ?: return ""
        return if (e.isJsonNull) "" else try { e.asString } catch (_: Exception) { "" }
    }

    private fun readDouble(obj: JsonObject, key: String): Double {
        val e = obj.get(key) ?: return 0.0
        return if (e.isJsonNull) 0.0 else try { e.asDouble } catch (_: Exception) { 0.0 }
    }

    private fun readInt(obj: JsonObject, key: String): Int {
        val e = obj.get(key) ?: return 0
        return if (e.isJsonNull) 0 else try { e.asInt } catch (_: Exception) { 0 }
    }

    private fun readStrArray(obj: JsonObject, key: String): List<String> {
        val arr = obj.getAsJsonArrayOrNull(key) ?: return emptyList()
        val out = ArrayList<String>()
        for (e in arr) {
            if (!e.isJsonNull && e.isJsonPrimitive && e.asJsonPrimitive.isString) {
                out.add(e.asString)
            }
        }
        return out
    }
}
