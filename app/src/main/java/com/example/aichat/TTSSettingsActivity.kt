package com.example.aichat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

class TTSSettingsActivity : ThemedActivity() {

    /** 选择器单项：title 是显示的标题，subtitle 是辅助说明，value 是写回输入框的值。 */
    private data class PickerOption(
        val title: String,
        val subtitle: String? = null,
        val value: String,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val store = TTSConfigStore(this)

        val switchEnabled = findViewById<MaterialSwitch?>(R.id.switchTTSEnabled)
        val radioMode = findViewById<RadioGroup?>(R.id.radioTTSMode)
        val layoutHttpApi = findViewById<View?>(R.id.layoutHttpApi)
        val layoutSdk = findViewById<View?>(R.id.layoutSdk)
        val editApiKey = findViewById<TextInputEditText?>(R.id.editTTSApiKey)
        val layoutResourceId = findViewById<TextInputLayout?>(R.id.layoutTTSResourceId)
        val editResourceId = findViewById<TextInputEditText?>(R.id.editTTSResourceId)
        val layoutEncoding = findViewById<TextInputLayout?>(R.id.layoutTTSEncoding)
        val editEncoding = findViewById<TextInputEditText?>(R.id.editTTSEncoding)
        val editAppId = findViewById<TextInputEditText?>(R.id.editTTSAppId)
        val editToken = findViewById<TextInputEditText?>(R.id.editTTSAccessToken)
        val layoutCluster = findViewById<TextInputLayout?>(R.id.layoutTTSCluster)
        val editCluster = findViewById<TextInputEditText?>(R.id.editTTSCluster)
        val layoutVoiceType = findViewById<TextInputLayout?>(R.id.layoutTTSVoiceType)
        val editVoiceType = findViewById<TextInputEditText?>(R.id.editTTSVoiceType)
        val seekSpeed = findViewById<SeekBar?>(R.id.seekTTSSpeed)
        val textSpeedValue = findViewById<TextView?>(R.id.textTTSSpeedValue)
        val seekVolume = findViewById<SeekBar?>(R.id.seekTTSVolume)
        val textVolumeValue = findViewById<TextView?>(R.id.textTTSVolumeValue)

        switchEnabled?.isChecked = store.isEnabled()
        editApiKey?.setText(store.getApiKey())
        editResourceId?.setText(store.getResourceId())
        editEncoding?.setText(store.getEncoding())
        editAppId?.setText(store.getAppId())
        editToken?.setText(store.getAccessToken())
        editCluster?.setText(store.getCluster())
        editVoiceType?.setText(store.getVoiceType())

        // ---- Resource ID picker ----
        val resourceOptions = TTSResourcePresets.all.map {
            PickerOption(title = it.resourceId, subtitle = it.description, value = it.resourceId)
        }
        val openResourcePicker: (View) -> Unit = {
            showPicker(
                title = "选择 Resource ID",
                options = resourceOptions,
                currentValue = editResourceId?.text?.toString()?.trim(),
                tip = "公版音色建议用 volc.service_type.10029；声音复刻用 volc.megatts.default。" +
                        "切音色预设会自动联动，这里也允许手动覆盖。",
            ) { picked ->
                editResourceId?.setText(picked.value)
            }
        }
        editResourceId?.setOnClickListener(openResourcePicker)
        layoutResourceId?.setEndIconOnClickListener(openResourcePicker)

        // ---- Encoding picker ----
        val encodingOptions = listOf(
            PickerOption("pcm", "流式播放，低延迟（推荐）", "pcm"),
            PickerOption("mp3", "压缩格式，文件缓冲后播放", "mp3"),
            PickerOption("ogg_opus", "Opus 编码，体积小", "ogg_opus"),
            PickerOption("wav", "无压缩，体积最大", "wav"),
        )
        val openEncodingPicker: (View) -> Unit = {
            showPicker(
                title = "选择 Encoding",
                options = encodingOptions,
                currentValue = editEncoding?.text?.toString()?.trim(),
                tip = "pcm 走流播低延迟；其它格式走文件缓冲。",
            ) { picked ->
                editEncoding?.setText(picked.value)
            }
        }
        editEncoding?.setOnClickListener(openEncodingPicker)
        layoutEncoding?.setEndIconOnClickListener(openEncodingPicker)

        // ---- Cluster picker ----
        val clusterOptions = listOf(
            PickerOption("volcano_tts", "标准合成", "volcano_tts"),
            PickerOption("volcano_icl", "声音复刻 ICL", "volcano_icl"),
        )
        val openClusterPicker: (View) -> Unit = {
            showPicker(
                title = "选择 Cluster",
                options = clusterOptions,
                currentValue = editCluster?.text?.toString()?.trim(),
                tip = "volcano_tts = 标准；volcano_icl = 声音复刻。",
            ) { picked ->
                editCluster?.setText(picked.value)
            }
        }
        editCluster?.setOnClickListener(openClusterPicker)
        layoutCluster?.setEndIconOnClickListener(openClusterPicker)

        // ---- Voice Type picker ----
        val voiceOptions = TTSVoicePresets.all.map {
            PickerOption(title = it.label, subtitle = it.speakerId, value = it.speakerId)
        }
        val openVoicePicker: (View) -> Unit = {
            showPicker(
                title = "选择预设音色",
                options = voiceOptions,
                currentValue = editVoiceType?.text?.toString()?.trim(),
                tip = "公版音色会同步把 Resource ID 设为 volc.service_type.10029。",
            ) { picked ->
                editVoiceType?.setText(picked.value)
                // 选预设时联动 Resource ID（之后用户仍可在 Resource ID picker 里手动改）
                TTSVoicePresets.findBySpeakerId(picked.value)?.let { preset ->
                    editResourceId?.setText(preset.resourceId)
                }
            }
        }
        editVoiceType?.setOnClickListener(openVoicePicker)
        layoutVoiceType?.setEndIconOnClickListener(openVoicePicker)

        fun updateModeVisibility(httpMode: Boolean) {
            layoutHttpApi?.visibility = if (httpMode) View.VISIBLE else View.GONE
            layoutSdk?.visibility = if (!httpMode) View.VISIBLE else View.GONE
        }
        val isHttpApi = store.isHttpApiMode()
        radioMode?.check(if (isHttpApi) R.id.modeHttpApi else R.id.modeSdk)
        updateModeVisibility(isHttpApi)
        radioMode?.setOnCheckedChangeListener { _, checkedId ->
            updateModeVisibility(checkedId == R.id.modeHttpApi)
        }

        // SeekBar: 0..15 maps to 0.5..2.0 (step 0.1)
        fun ratioToProgress(ratio: Float): Int = ((ratio - 0.5f) * 10f).toInt().coerceIn(0, 15)
        fun progressToRatio(progress: Int): Float = 0.5f + progress * 0.1f

        seekSpeed?.progress = ratioToProgress(store.getSpeedRatio())
        textSpeedValue?.text = String.format(Locale.US, "%.1fx", store.getSpeedRatio())
        seekSpeed?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                textSpeedValue?.text = String.format(Locale.US, "%.1fx", progressToRatio(progress))
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        seekVolume?.progress = ratioToProgress(store.getVolumeRatio())
        textVolumeValue?.text = String.format(Locale.US, "%.1fx", store.getVolumeRatio())
        seekVolume?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                textVolumeValue?.text = String.format(Locale.US, "%.1fx", progressToRatio(progress))
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        val btnSave = findViewById<MaterialButton>(R.id.btnSaveTTSSettings)
        btnSave.setOnClickListener {
            store.saveAll(
                enabled = switchEnabled?.isChecked == true,
                useHttpApi = radioMode?.checkedRadioButtonId == R.id.modeHttpApi,
                appId = editAppId?.text?.toString(),
                accessToken = editToken?.text?.toString(),
                apiKey = editApiKey?.text?.toString(),
                encoding = editEncoding?.text?.toString(),
                resourceId = editResourceId?.text?.toString(),
                cluster = editCluster?.text?.toString(),
                voiceType = editVoiceType?.text?.toString(),
                speedRatio = progressToRatio(seekSpeed?.progress ?: 5),
                volumeRatio = progressToRatio(seekVolume?.progress ?: 5),
            )
            Toast.makeText(this, "语音合成设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * 通用 picker 弹窗：玻璃卡片 + 列表 + 底部 tip。
     * 命中当前值的项会显示打勾。点项即关闭并回调。
     */
    private fun showPicker(
        title: String,
        options: List<PickerOption>,
        currentValue: String?,
        tip: String?,
        onPicked: (PickerOption) -> Unit,
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_tts_picker, null, false)
        view.findViewById<TextView>(R.id.textPickerTitle).text = title
        val container = view.findViewById<LinearLayout>(R.id.containerPickerItems)
        val tipText = view.findViewById<TextView>(R.id.textPickerTip)
        val tipDivider = view.findViewById<View>(R.id.dividerPickerTip)
        if (!tip.isNullOrBlank()) {
            tipText.text = tip
            tipText.visibility = View.VISIBLE
            tipDivider.visibility = View.VISIBLE
        }

        val dialog: AlertDialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .create()

        val inflater = LayoutInflater.from(this)
        options.forEach { option ->
            val item = inflater.inflate(R.layout.item_tts_picker, container, false)
            item.findViewById<TextView>(R.id.textPickerItemTitle).text = option.title
            val sub = item.findViewById<TextView>(R.id.textPickerItemSubtitle)
            if (option.subtitle.isNullOrBlank()) {
                sub.visibility = View.GONE
            } else {
                sub.text = option.subtitle
                sub.visibility = View.VISIBLE
            }
            val check = item.findViewById<ImageView>(R.id.iconPickerItemCheck)
            check.visibility = if (option.value == currentValue) View.VISIBLE else View.INVISIBLE
            item.setOnClickListener {
                onPicked(option)
                dialog.dismiss()
            }
            container.addView(
                item,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        dialog.show()
    }
}
