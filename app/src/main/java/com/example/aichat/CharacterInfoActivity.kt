package com.example.aichat

import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.example.aichat.sync.CharacterBootstrapStore
import com.google.android.material.appbar.MaterialToolbar
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 展示当前会话绑定的角色最近一次 /api/character/context 响应内容.
 * 从 CharacterBootstrapStore 的内存缓存读取, 无网络请求.
 */
class CharacterInfoActivity : ThemedActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }

    private val prettyGson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_character_info)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val contentView = findViewById<TextView>(R.id.textContent)
        val emptyView = findViewById<TextView>(R.id.textEmpty)
        val fetchedAtView = findViewById<TextView>(R.id.textFetchedAt)

        val sid = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val assistantId = if (sid.isNotEmpty())
            SessionAssistantBindingStore(this).getAssistantId(sid) else ""
        val cache = CharacterBootstrapStore.getInstance(this).getCached(assistantId)

        if (cache == null || cache.rawJson.isBlank()) {
            emptyView.visibility = View.VISIBLE
            contentView.visibility = View.GONE
            fetchedAtView.visibility = View.GONE
            return
        }

        fetchedAtView.text = getString(R.string.character_info_fetched_at,
            timeFmt.format(Date(cache.fetchedAtMs)))

        contentView.text = try {
            prettyGson.toJson(JsonParser().parse(cache.rawJson))
        } catch (_: Exception) {
            cache.rawJson
        }
    }
}
