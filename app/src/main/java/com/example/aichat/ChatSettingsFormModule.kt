package com.example.aichat

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 可复用的聊天设置表单模块。
 */
class ChatSettingsFormModule(private val activity: Activity, private val root: View) {

    companion object {
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
    private val sliderContextCount: Slider? = root.findViewById(R.id.sliderContextCount)
    private val textContextCountValue: TextView? = root.findViewById(R.id.textContextCountValue)
    private val switchAutoChapterPlan: MaterialSwitch? = root.findViewById(R.id.switchAutoChapterPlan)
    private val switchThinking: MaterialSwitch? = root.findViewById(R.id.switchThinking)
    private val layoutGoogleThinkingBudget: TextInputLayout? = root.findViewById(R.id.layoutGoogleThinkingBudget)
    private val editGoogleThinkingBudget: TextInputEditText? = root.findViewById(R.id.editGoogleThinkingBudget)

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
        switchThinking?.setOnCheckedChangeListener { _, isChecked ->
            layoutGoogleThinkingBudget?.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
    }

    fun setOptions(options: SessionChatOptions?) {
        current = options ?: SessionChatOptions()
        updateModelText()
        editSystemPrompt?.setText(current.systemPrompt)
        editTemperature?.setText(current.temperature.toString())
        editTopP?.setText(current.topP.toString())
        sliderContextCount?.let { slider ->
            val count = current.contextMessageCount
            val position = mapContextValueToSliderPosition(count)
            slider.value = position
            updateContextCountValue(mapSliderPositionToContextValue(position))
        }
        switchThinking?.isChecked = current.thinking
        switchAutoChapterPlan?.isChecked = current.autoChapterPlan
        editGoogleThinkingBudget?.setText(
            (if (current.googleThinkingBudget > 0) current.googleThinkingBudget else 1024).toString()
        )
        layoutGoogleThinkingBudget?.visibility = if (current.thinking) View.VISIBLE else View.GONE
    }

    fun collect(): SessionChatOptions {
        val out = SessionChatOptions()
        out.modelKey = current.modelKey ?: ""
        out.systemPrompt = editSystemPrompt?.text?.toString()?.trim() ?: ""
        // Stop sequence is hidden in UI; preserve existing value for compatibility.
        out.stop = current.stop ?: ""
        out.temperature = parseFloat(editTemperature, 0.7f)
        out.topP = parseFloat(editTopP, 1.0f)
        out.contextMessageCount = getContextCount()
        out.streamOutput = true
        out.autoChapterPlan = switchAutoChapterPlan?.isChecked == true
        out.thinking = switchThinking?.isChecked == true
        out.googleThinkingBudget = parseInt(editGoogleThinkingBudget, 1024)
        return out
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
