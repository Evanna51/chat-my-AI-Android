package com.example.aichat

import android.content.res.TypedArray
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** 模型配置：为各任务选用模型预设 */
class ModelConfigActivity : ThemedActivity() {

    private lateinit var modelConfig: ModelConfig
    private var textChatModel: TextView? = null
    private var textThreadNamingModel: TextView? = null
    private var textSearchModel: TextView? = null
    private var textSummaryModel: TextView? = null
    private var textNovelSharpModel: TextView? = null
    private var chatPreset: String? = null
    private var threadNamingPreset: String? = null
    private var searchPreset: String? = null
    private var summaryPreset: String? = null
    private var novelSharpPreset: String? = null
    private var switchHomeMode: MaterialSwitch? = null
    private var editingHomeMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_config)

        modelConfig = ModelConfig(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        textChatModel = findViewById(R.id.textChatModel)
        textThreadNamingModel = findViewById(R.id.textThreadNamingModel)
        textSearchModel = findViewById(R.id.textSearchModel)
        textSummaryModel = findViewById(R.id.textSummaryModel)
        textNovelSharpModel = findViewById(R.id.textNovelSharpModel)
        switchHomeMode = findViewById(R.id.switchHomeMode)

        switchHomeMode?.let { sw ->
            editingHomeMode = modelConfig.isHomeModeEnabled()
            sw.isChecked = editingHomeMode
            sw.setOnCheckedChangeListener { _, isChecked ->
                editingHomeMode = isChecked
                refreshDisplay()
            }
        }

        refreshDisplay()

        findViewById<View>(R.id.cardChatModel).setOnClickListener { showPicker(0) }
        findViewById<View>(R.id.cardThreadNamingModel).setOnClickListener { showPicker(1) }
        findViewById<View>(R.id.cardSearchModel).setOnClickListener { showPicker(2) }
        findViewById<View>(R.id.cardSummaryModel).setOnClickListener { showPicker(3) }
        findViewById<View>(R.id.cardNovelSharpModel).setOnClickListener { showPicker(4) }

        val btnSave = findViewById<MaterialButton>(R.id.btnSave)
        btnSave.setOnClickListener {
            if (editingHomeMode) {
                modelConfig.setHomeChatPreset(chatPreset ?: "")
                modelConfig.setHomeThreadNamingPreset(threadNamingPreset ?: "")
                modelConfig.setHomeSearchPreset(searchPreset ?: "")
                modelConfig.setHomeSummaryPreset(summaryPreset ?: "")
                modelConfig.setHomeNovelSharpPreset(novelSharpPreset ?: "")
            } else {
                modelConfig.setAwayChatPreset(chatPreset ?: "")
                modelConfig.setAwayThreadNamingPreset(threadNamingPreset ?: "")
                modelConfig.setAwaySearchPreset(searchPreset ?: "")
                modelConfig.setAwaySummaryPreset(summaryPreset ?: "")
                modelConfig.setAwayNovelSharpPreset(novelSharpPreset ?: "")
            }
            modelConfig.setHomeModeEnabled(editingHomeMode)
            syncToConfigManager()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDisplay()
    }

    private fun refreshDisplay() {
        if (editingHomeMode) {
            chatPreset = modelConfig.getHomeChatPreset()
            threadNamingPreset = modelConfig.getHomeThreadNamingPreset()
            searchPreset = modelConfig.getHomeSearchPreset()
            summaryPreset = modelConfig.getHomeSummaryPreset()
            novelSharpPreset = modelConfig.getHomeNovelSharpPreset()
        } else {
            chatPreset = modelConfig.getAwayChatPreset()
            threadNamingPreset = modelConfig.getAwayThreadNamingPreset()
            searchPreset = modelConfig.getAwaySearchPreset()
            summaryPreset = modelConfig.getAwaySummaryPreset()
            novelSharpPreset = modelConfig.getAwayNovelSharpPreset()
        }
        updateText(textChatModel, chatPreset)
        updateText(textThreadNamingModel, threadNamingPreset)
        updateText(textSearchModel, searchPreset)
        updateText(textSummaryModel, summaryPreset)
        updateText(textNovelSharpModel, novelSharpPreset)
    }

    private fun updateText(tv: TextView?, storageKey: String?) {
        if (tv == null) return
        if (storageKey.isNullOrEmpty()) {
            tv.text = "请选择已配置的模型"
            tv.setTextColor(0xFF9E9E9E.toInt())
            return
        }
        try {
            val o = ConfiguredModelPicker.Option.fromStorageKey(storageKey, this)
            if (o != null && o.displayName != null && o.providerName != null) {
                tv.text = "${o.displayName} (${o.providerName})"
                val a: TypedArray = tv.context.theme.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
                tv.setTextColor(a.getColor(0, 0xFF212121.toInt()))
                a.recycle()
            } else {
                tv.text = "$storageKey（厂商或模型已移除）"
                tv.setTextColor(0xFF9E9E9E.toInt())
            }
        } catch (e: Exception) {
            tv.text = storageKey
            tv.setTextColor(0xFF9E9E9E.toInt())
        }
    }

    private fun showPicker(field: Int) {
        val options = ConfiguredModelPicker.getConfiguredModels(this)
        if (options.isEmpty()) {
            Toast.makeText(this, "请先在「模型管理」中添加厂商并配置 API Key、获取并添加模型", Toast.LENGTH_LONG).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_model_picker, null)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.recyclerOptions)
        recycler.layoutManager = LinearLayoutManager(this)

        val titles = arrayOf("对话选用", "话题命名选用", "搜索选用", "总结选用", "小说敏锐选用")
        val currentKey = when (field) {
            0 -> chatPreset
            1 -> threadNamingPreset
            2 -> searchPreset
            3 -> summaryPreset
            else -> novelSharpPreset
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titles[field])
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        val adapter = ModelPickerAdapter(options, currentKey) { option ->
            when (field) {
                0 -> { chatPreset = option.getStorageKey(); updateText(textChatModel, chatPreset) }
                1 -> { threadNamingPreset = option.getStorageKey(); updateText(textThreadNamingModel, threadNamingPreset) }
                2 -> { searchPreset = option.getStorageKey(); updateText(textSearchModel, searchPreset) }
                3 -> { summaryPreset = option.getStorageKey(); updateText(textSummaryModel, summaryPreset) }
                else -> { novelSharpPreset = option.getStorageKey(); updateText(textNovelSharpModel, novelSharpPreset) }
            }
            dialog.dismiss()
        }
        recycler.adapter = adapter
        dialog.show()
    }

    private fun syncToConfigManager() {
        val cm = ConfigManager(this)
        cm.setModel(modelConfig.getChatPreset())
        cm.setThreadNamingModel(modelConfig.getThreadNamingPreset())
        cm.setSearchModel(modelConfig.getSearchPreset())
        cm.setSummaryModel(modelConfig.getSummaryPreset())
    }
}
