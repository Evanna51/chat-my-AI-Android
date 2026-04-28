package com.example.aichat

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

class TTSSettingsActivity : ThemedActivity() {

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
        val editResourceId = findViewById<MaterialAutoCompleteTextView?>(R.id.editTTSResourceId)
        val editEncoding = findViewById<MaterialAutoCompleteTextView?>(R.id.editTTSEncoding)
        val editAppId = findViewById<TextInputEditText?>(R.id.editTTSAppId)
        val editToken = findViewById<TextInputEditText?>(R.id.editTTSAccessToken)
        val editCluster = findViewById<MaterialAutoCompleteTextView?>(R.id.editTTSCluster)
        val layoutVoiceType = findViewById<TextInputLayout?>(R.id.layoutTTSVoiceType)
        val editVoiceType = findViewById<TextInputEditText?>(R.id.editTTSVoiceType)
        val seekSpeed = findViewById<SeekBar?>(R.id.seekTTSSpeed)
        val textSpeedValue = findViewById<TextView?>(R.id.textTTSSpeedValue)
        val seekVolume = findViewById<SeekBar?>(R.id.seekTTSVolume)
        val textVolumeValue = findViewById<TextView?>(R.id.textTTSVolumeValue)

        switchEnabled?.isChecked = store.isEnabled()
        editApiKey?.setText(store.getApiKey())
        editResourceId?.setText(store.getResourceId(), false)
        editResourceId?.let { dropdown ->
            val resources = TTSResourcePresets.all
            val resAdapter = ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                resources.map { it.display },
            )
            dropdown.setAdapter(resAdapter)
            dropdown.setOnItemClickListener { _, _, position, _ ->
                dropdown.setText(resources[position].resourceId, false)
            }
        }
        editEncoding?.setText(store.getEncoding(), false)
        editEncoding?.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                listOf("pcm", "mp3", "ogg_opus", "wav"),
            )
        )
        editAppId?.setText(store.getAppId())
        editToken?.setText(store.getAccessToken())
        editCluster?.setText(store.getCluster(), false)
        editCluster?.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                listOf("volcano_tts", "volcano_icl"),
            )
        )
        editVoiceType?.setText(store.getVoiceType())
        layoutVoiceType?.setEndIconOnClickListener {
            val presets = TTSVoicePresets.all
            val current = editVoiceType?.text?.toString()?.trim()
            val checked = presets.indexOfFirst { it.speakerId == current }
            // 单选列表项默认只显示一行；用 \n 把 label 和 speakerId 拆两行，
            // 这样完整 speaker_id 也能看到。
            val items = presets.map { "${it.label}\n${it.speakerId}" }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle("选择预设音色")
                .setSingleChoiceItems(items, checked) { dlg, which ->
                    val preset = presets[which]
                    editVoiceType?.setText(preset.speakerId)
                    editResourceId?.setText(preset.resourceId)
                    dlg.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

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
}
