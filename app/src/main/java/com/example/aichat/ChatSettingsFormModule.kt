package com.example.aichat

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aichat.chat.ProactiveBudget
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText

/**
 * 可复用的聊天设置表单模块。
 */
class ChatSettingsFormModule(private val activity: Activity, private val root: View) {

    /** 单实例内"我已经主动 request 过一次"的状态 — 没有跨实例需求 (设置页不长存). */
    private var alreadyAskedNotificationPermission = false

    companion object {
        /** 通知权限请求 code. 我们不用 onRequestPermissionsResult 区分, 取值任意. */
        private const val REQ_NOTIF_PERM = 5511

        private val CONTEXT_SLIDER_POSITIONS: FloatArray = buildContextSliderPositions()
        private val CONTEXT_SLIDER_VALUES: IntArray = buildContextSliderValues()

        private fun buildContextSliderValues(): IntArray {
            val values = IntArray(26)
            for (i in 0..10) {
                values[i] = i
            }
            for (i in 0..14) {
                values[11 + i] = 16 + i * 8 // 16..128
            }
            return values
        }

        private fun buildContextSliderPositions(): FloatArray {
            val positions = FloatArray(26)
            for (i in 0..10) {
                positions[i] = i * 6f // 0..60
            }
            val step = 40f / 14f // 60..100 split into 15 nodes
            for (i in 0..14) {
                positions[11 + i] = 60f + i * step
            }
            return positions
        }
    }

    private val textModelValue: TextView? = root.findViewById(R.id.textModelValue)
    private val btnPickModel: View? = root.findViewById(R.id.btnPickModel)
    private val editSystemPrompt: TextInputEditText? = root.findViewById(R.id.editSystemPrompt)
    private val editTemperature: TextInputEditText? = root.findViewById(R.id.editTemperature)
    private val editTopP: TextInputEditText? = root.findViewById(R.id.editTopP)
    private val editMaxTokens: TextInputEditText? = root.findViewById(R.id.editMaxTokens)
    private val editTopK: TextInputEditText? = root.findViewById(R.id.editTopK)
    private val editFrequencyPenalty: TextInputEditText? = root.findViewById(R.id.editFrequencyPenalty)
    private val editPresencePenalty: TextInputEditText? = root.findViewById(R.id.editPresencePenalty)
    private val sliderContextCount: Slider? = root.findViewById(R.id.sliderContextCount)
    private val textContextCountValue: TextView? = root.findViewById(R.id.textContextCountValue)
    private val switchAutoChat: MaterialSwitch? = root.findViewById(R.id.switchAutoChat)
    private val textAutoChatBudgetHint: TextView? = root.findViewById(R.id.textAutoChatBudgetHint)
    private val editAutoChatDailyBudget: TextInputEditText? = root.findViewById(R.id.editAutoChatDailyBudget)

    private var current = SessionChatOptions()

    init {
        FormInputScrollHelper.enableFor(editSystemPrompt)
        sliderContextCount?.apply {
            valueFrom = 0f
            valueTo = 100f
            stepSize = 0f
            setLabelFormatter { value -> mapSliderPositionToContextValue(value).toString() }
            addOnChangeListener { _, value, _ ->
                updateContextCountValue(mapSliderPositionToContextValue(value))
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    val snapped = snapSliderPosition(slider.value)
                    if (Math.abs(snapped - slider.value) > 0.0001f) {
                        slider.value = snapped
                    }
                    updateContextCountValue(mapSliderPositionToContextValue(snapped))
                }
            })
        }

        btnPickModel?.setOnClickListener { showModelPicker() }

        switchAutoChat?.setOnCheckedChangeListener { _, checked ->
            current.autoChatEnabled = checked
            updateAutoChatBudgetHint()
            if (checked) maybeRequestNotificationPermission()
        }
        editAutoChatDailyBudget?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                current.proactiveDailyBudget = (s?.toString()?.trim()?.toIntOrNull() ?: 0)
                    .coerceIn(0, ProactiveBudget.MAX_DAILY_BUDGET)
                updateAutoChatBudgetHint()
            }
        })
    }

    fun setOptions(options: SessionChatOptions?) {
        current = options ?: SessionChatOptions()
        updateModelText()
        editSystemPrompt?.setText(current.systemPrompt)
        editTemperature?.setText(current.temperature.toString())
        editTopP?.setText(current.topP.toString())
        editMaxTokens?.setText(current.maxTokens?.toString().orEmpty())
        editTopK?.setText(current.topK?.toString().orEmpty())
        editFrequencyPenalty?.setText(current.frequencyPenalty?.toString().orEmpty())
        editPresencePenalty?.setText(current.presencePenalty?.toString().orEmpty())
        sliderContextCount?.let { slider ->
            val count = current.contextMessageCount
            val position = mapContextValueToSliderPosition(count)
            slider.value = position
            updateContextCountValue(mapSliderPositionToContextValue(position))
        }
        switchAutoChat?.isChecked = current.autoChatEnabled
        editAutoChatDailyBudget?.setText(if (current.proactiveDailyBudget > 0)
            current.proactiveDailyBudget.toString() else "")
        updateAutoChatBudgetHint()
    }

    /**
     * 用户开启 [自动对话] toggle 时, 引导授予通知权限. Android 13+ 必须用户
     * 显式同意 POST_NOTIFICATIONS, 否则后台 Worker 触发的 follow-up 推送看不见.
     *
     * 行为:
     *   - API < 33: 无需权限, no-op
     *   - 已授权: no-op
     *   - 第一次提示, 或用户之前选了 "ask again" 没选 "deny": 系统标准弹窗
     *   - 用户之前选了 "永远拒绝": 弹一个 AlertDialog 引导去系统设置
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val perm = Manifest.permission.POST_NOTIFICATIONS
        val granted = ContextCompat.checkSelfPermission(activity, perm) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        val canShowSystemDialog = ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
        // 第一次请求时 shouldShow=false 系统也会弹; 二次以上拒绝后 shouldShow=true 也仍可弹.
        // 永远拒绝 (Android "Don't ask again" 等价) 时弹会瞬间被驳回, 退而显示去设置的引导.
        if (alreadyAskedNotificationPermission && !canShowSystemDialog) {
            promptOpenAppSettingsForNotifications()
            return
        }
        alreadyAskedNotificationPermission = true
        try {
            ActivityCompat.requestPermissions(activity, arrayOf(perm), REQ_NOTIF_PERM)
        } catch (_: Exception) {
            promptOpenAppSettingsForNotifications()
        }
    }

    private fun promptOpenAppSettingsForNotifications() {
        try {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.auto_chat_notif_perm_title)
                .setMessage(R.string.auto_chat_notif_perm_message)
                .setPositiveButton(R.string.auto_chat_notif_perm_open) { _, _ ->
                    try {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                        )
                        intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName)
                        activity.startActivity(intent)
                    } catch (_: Exception) {}
                }
                .setNegativeButton(R.string.auto_chat_notif_perm_skip, null)
                .show()
        } catch (_: Exception) {}
    }

    private fun updateAutoChatBudgetHint() {
        val hintView = textAutoChatBudgetHint ?: return
        val today = ProactiveBudget.todayStamp()
        val used = if (current.proactiveResetDate == today) current.proactiveCountToday else 0
        if (current.autoChatEnabled) {
            val limit = ProactiveBudget.effectiveLimit(current.proactiveDailyBudget)
            hintView.text = activity.getString(
                R.string.auto_chat_budget_used, used, limit
            )
        } else {
            hintView.setText(R.string.auto_chat_toggle_hint)
        }
    }

    fun collect(): SessionChatOptions {
        val out = SessionChatOptions()
        out.modelKey = current.modelKey ?: ""
        out.systemPrompt = editSystemPrompt?.text?.toString()?.trim() ?: ""
        // Stop sequence is hidden in UI; preserve existing value for compatibility.
        out.stop = current.stop ?: ""
        out.temperature = parseFloat(editTemperature, 0.7f)
        out.topP = parseFloat(editTopP, 1.0f)
        // 留空 = null = 跟随上游
        out.maxTokens = parseNullableInt(editMaxTokens)
        out.topK = parseNullableInt(editTopK)
        out.frequencyPenalty = parseNullableFloat(editFrequencyPenalty)
        out.presencePenalty = parseNullableFloat(editPresencePenalty)
        out.contextMessageCount = getContextCount()
        out.streamOutput = true
        // autoChapterPlan is deprecated: chapter plan is now triggered manually from outline page.
        // Preserve existing value for backward compat (never written back as true via UI).
        out.autoChapterPlan = current.autoChapterPlan
        // Thinking is now a model capability (set in provider config), not a session toggle.
        // Preserve the stored value so existing sessions with thinking=true keep working.
        out.thinking = current.thinking
        out.googleThinkingBudget = current.googleThinkingBudget
        // 自动对话: 用户控制的开关 + 每日上限; 预算计数器原样保留 (跨会话写回不会被清零).
        out.autoChatEnabled = switchAutoChat?.isChecked ?: current.autoChatEnabled
        out.proactiveCountToday = current.proactiveCountToday
        out.proactiveResetDate = current.proactiveResetDate
        out.proactiveDailyBudget = parseNullableInt(editAutoChatDailyBudget)
            ?.coerceIn(ProactiveBudget.MIN_DAILY_BUDGET, ProactiveBudget.MAX_DAILY_BUDGET)
            ?: 0
        return out
    }

    private fun parseNullableInt(edit: TextInputEditText?): Int? {
        val s = edit?.text?.toString()?.trim().orEmpty()
        if (s.isEmpty()) return null
        return s.toIntOrNull()
    }

    private fun parseNullableFloat(edit: TextInputEditText?): Float? {
        val s = edit?.text?.toString()?.trim().orEmpty()
        if (s.isEmpty()) return null
        return s.toFloatOrNull()
    }

    private fun getContextCount(): Int {
        return if (sliderContextCount == null) 6
        else mapSliderPositionToContextValue(sliderContextCount.value)
    }

    private fun updateContextCountValue(value: Int) {
        textContextCountValue?.setText(value.toString())
    }

    private fun snapSliderPosition(raw: Float): Float {
        var nearest = 0
        var minDiff = Float.MAX_VALUE
        for (i in CONTEXT_SLIDER_POSITIONS.indices) {
            val diff = Math.abs(raw - CONTEXT_SLIDER_POSITIONS[i])
            if (diff < minDiff) {
                minDiff = diff
                nearest = i
            }
        }
        return CONTEXT_SLIDER_POSITIONS[nearest]
    }

    private fun mapSliderPositionToContextValue(position: Float): Int {
        var nearest = 0
        var minDiff = Float.MAX_VALUE
        for (i in CONTEXT_SLIDER_POSITIONS.indices) {
            val diff = Math.abs(position - CONTEXT_SLIDER_POSITIONS[i])
            if (diff < minDiff) {
                minDiff = diff
                nearest = i
            }
        }
        return CONTEXT_SLIDER_VALUES[nearest]
    }

    private fun mapContextValueToSliderPosition(contextValue: Int): Float {
        var nearest = 0
        var minDiff = Int.MAX_VALUE
        for (i in CONTEXT_SLIDER_VALUES.indices) {
            val diff = Math.abs(contextValue - CONTEXT_SLIDER_VALUES[i])
            if (diff < minDiff) {
                minDiff = diff
                nearest = i
            }
        }
        return CONTEXT_SLIDER_POSITIONS[nearest]
    }

    private fun parseFloat(edit: TextInputEditText?, def: Float): Float {
        return try {
            if (edit == null || edit.text == null) return def
            val s = edit.text.toString().trim()
            if (s.isEmpty()) def else s.toFloat()
        } catch (e: Exception) {
            def
        }
    }

    private fun parseInt(edit: TextInputEditText?, def: Int): Int {
        return try {
            if (edit == null || edit.text == null) return def
            val s = edit.text.toString().trim()
            if (s.isEmpty()) def else maxOf(s.toInt(), 0)
        } catch (e: Exception) {
            def
        }
    }

    private fun updateModelText() {
        if (textModelValue == null) return
        if (current.modelKey.isNullOrEmpty()) {
            textModelValue.setText("请选择模型")
            return
        }
        val option = ConfiguredModelPicker.Option.fromStorageKey(current.modelKey, activity)
        if (option != null) {
            textModelValue.setText("${option.displayName} (${option.providerName})")
        } else {
            textModelValue.setText(current.modelKey)
        }
    }

    private fun showModelPicker() {
        val options = ConfiguredModelPicker.getConfiguredModels(activity)
        if (options == null || options.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setMessage("请先在「模型管理」中添加厂商并添加模型")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_model_picker, null)
        val recycler: RecyclerView = dialogView.findViewById(R.id.recyclerOptions)
        recycler.layoutManager = LinearLayoutManager(activity)

        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setTitle("选择模型")
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        val adapter = ModelPickerAdapter(options, current.modelKey) { option ->
            current.modelKey = option.getStorageKey()
            updateModelText()
            dialog.dismiss()
        }
        recycler.adapter = adapter
        dialog.show()
    }
}
