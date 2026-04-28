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
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 模型配置：为各任务选用模型预设，按场景（居家/外出/自定义）三套独立维护 */
class ModelConfigActivity : ThemedActivity() {

    private lateinit var modelConfig: ModelConfig
    private var textChatModel: TextView? = null
    private var textThreadNamingModel: TextView? = null
    private var textSearchModel: TextView? = null
    private var textSummaryModel: TextView? = null
    private var textNovelSharpModel: TextView? = null
    private var textEmbeddingModel: TextView? = null

    private val drafts: MutableMap<ModelConfig.Scene, MutableMap<ModelConfig.Field, String>> =
        EnumMap(ModelConfig.Scene::class.java)
    private var editingScene: ModelConfig.Scene = ModelConfig.Scene.HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_config)

        modelConfig = ModelConfig(this)
        loadAllScenesIntoDrafts()
        editingScene = modelConfig.getActiveScene()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        textChatModel = findViewById(R.id.textChatModel)
        textThreadNamingModel = findViewById(R.id.textThreadNamingModel)
        textSearchModel = findViewById(R.id.textSearchModel)
        textSummaryModel = findViewById(R.id.textSummaryModel)
        textNovelSharpModel = findViewById(R.id.textNovelSharpModel)
        textEmbeddingModel = findViewById(R.id.textEmbeddingModel)

        val toggleGroup = findViewById<MaterialButtonToggleGroup>(R.id.sceneToggleGroup)
        toggleGroup.check(buttonIdForScene(editingScene))
        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            editingScene = sceneForButtonId(checkedId) ?: ModelConfig.Scene.HOME
            refreshDisplay()
        }

        refreshDisplay()

        findViewById<View>(R.id.cardChatModel).setOnClickListener { showPicker(ModelConfig.Field.CHAT) }
        findViewById<View>(R.id.cardThreadNamingModel).setOnClickListener { showPicker(ModelConfig.Field.THREAD_NAMING) }
        findViewById<View>(R.id.cardSearchModel).setOnClickListener { showPicker(ModelConfig.Field.SEARCH) }
        findViewById<View>(R.id.cardSummaryModel).setOnClickListener { showPicker(ModelConfig.Field.SUMMARY) }
        findViewById<View>(R.id.cardNovelSharpModel).setOnClickListener { showPicker(ModelConfig.Field.NOVEL_SHARP) }
        findViewById<View>(R.id.cardEmbeddingModel).setOnClickListener { showPicker(ModelConfig.Field.EMBEDDING) }

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            for (scene in ModelConfig.Scene.values()) {
                val sceneDraft = drafts[scene] ?: continue
                for (field in ModelConfig.Field.values()) {
                    modelConfig.setPreset(scene, field, sceneDraft[field] ?: "")
                }
            }
            modelConfig.setActiveScene(editingScene)
            syncToConfigManager()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-sync drafts in case provider/model list changed externally; preserve user's tab.
        loadAllScenesIntoDrafts()
        refreshDisplay()
    }

    private fun loadAllScenesIntoDrafts() {
        for (scene in ModelConfig.Scene.values()) {
            val map = drafts.getOrPut(scene) { EnumMap(ModelConfig.Field::class.java) }
            for (field in ModelConfig.Field.values()) {
                map[field] = modelConfig.getPreset(scene, field)
            }
        }
    }

    private fun draftFor(field: ModelConfig.Field): String =
        drafts[editingScene]?.get(field) ?: ""

    private fun setDraft(field: ModelConfig.Field, value: String) {
        drafts.getOrPut(editingScene) { EnumMap(ModelConfig.Field::class.java) }[field] = value
    }

    private fun refreshDisplay() {
        updateText(textChatModel, draftFor(ModelConfig.Field.CHAT))
        updateText(textThreadNamingModel, draftFor(ModelConfig.Field.THREAD_NAMING))
        updateText(textSearchModel, draftFor(ModelConfig.Field.SEARCH))
        updateText(textSummaryModel, draftFor(ModelConfig.Field.SUMMARY))
        updateText(textNovelSharpModel, draftFor(ModelConfig.Field.NOVEL_SHARP))
        updateText(textEmbeddingModel, draftFor(ModelConfig.Field.EMBEDDING))
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
            if (o != null && !o.displayName.isNullOrEmpty()) {
                tv.text = o.displayName
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

    private fun showPicker(field: ModelConfig.Field) {
        val options = ConfiguredModelPicker.getConfiguredModels(this)
        if (options.isEmpty()) {
            Toast.makeText(this, "请先在「模型管理」中添加厂商并配置 API Key、获取并添加模型", Toast.LENGTH_LONG).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_model_picker, null)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.recyclerOptions)
        recycler.layoutManager = LinearLayoutManager(this)

        val title = "${sceneTitle(editingScene)} · ${fieldTitle(field)}"
        val currentKey = draftFor(field)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        val targetTextView = textViewFor(field)
        recycler.adapter = ModelPickerAdapter(options, currentKey) { option ->
            val key = option.getStorageKey()
            setDraft(field, key)
            updateText(targetTextView, key)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun textViewFor(field: ModelConfig.Field): TextView? = when (field) {
        ModelConfig.Field.CHAT -> textChatModel
        ModelConfig.Field.THREAD_NAMING -> textThreadNamingModel
        ModelConfig.Field.SEARCH -> textSearchModel
        ModelConfig.Field.SUMMARY -> textSummaryModel
        ModelConfig.Field.NOVEL_SHARP -> textNovelSharpModel
        ModelConfig.Field.EMBEDDING -> textEmbeddingModel
    }

    private fun fieldTitle(field: ModelConfig.Field): String = when (field) {
        ModelConfig.Field.CHAT -> "对话选用"
        ModelConfig.Field.THREAD_NAMING -> "话题命名选用"
        ModelConfig.Field.SEARCH -> "搜索选用"
        ModelConfig.Field.SUMMARY -> "总结选用"
        ModelConfig.Field.NOVEL_SHARP -> "小说敏锐选用"
        ModelConfig.Field.EMBEDDING -> "嵌入模型选用"
    }

    private fun sceneTitle(scene: ModelConfig.Scene): String = when (scene) {
        ModelConfig.Scene.HOME -> getString(R.string.scene_home)
        ModelConfig.Scene.AWAY -> getString(R.string.scene_away)
        ModelConfig.Scene.CUSTOM -> getString(R.string.scene_custom)
    }

    private fun buttonIdForScene(scene: ModelConfig.Scene): Int = when (scene) {
        ModelConfig.Scene.HOME -> R.id.sceneTabHome
        ModelConfig.Scene.AWAY -> R.id.sceneTabAway
        ModelConfig.Scene.CUSTOM -> R.id.sceneTabCustom
    }

    private fun sceneForButtonId(id: Int): ModelConfig.Scene? = when (id) {
        R.id.sceneTabHome -> ModelConfig.Scene.HOME
        R.id.sceneTabAway -> ModelConfig.Scene.AWAY
        R.id.sceneTabCustom -> ModelConfig.Scene.CUSTOM
        else -> null
    }

    private fun syncToConfigManager() {
        val cm = ConfigManager(this)
        cm.setModel(modelConfig.getChatPreset())
        cm.setThreadNamingModel(modelConfig.getThreadNamingPreset())
        cm.setSearchModel(modelConfig.getSearchPreset())
        cm.setSummaryModel(modelConfig.getSummaryPreset())
    }
}

private typealias EnumMap<K, V> = java.util.EnumMap<K, V>
