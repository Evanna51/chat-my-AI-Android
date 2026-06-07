package com.example.aichat

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.example.aichat.inkos.InkosSubtypePresets
import com.example.aichat.session.SessionMode
import com.example.aichat.session.mode
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SessionChatSettingsActivity : ThemedActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }

    private var sessionId: String? = null
    private lateinit var store: SessionChatOptionsStore
    private lateinit var formModule: ChatSettingsFormModule
    private var editSessionTitle: TextInputEditText? = null

    /** 当前会话设置中的头像状态（图片 + 文字），由 UI 修改、保存时写回 options。 */
    private var draftAvatarImage: String = ""

    private var imageAvatarPreview: ImageView? = null
    private var textAvatarPreview: TextView? = null
    private var btnClearAvatarImage: View? = null

    /** 用于显示「fallback to assistant」时的虚拟 MyAssistant，保证回退头像正确。 */
    private var boundAssistant: MyAssistant? = null

    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_chat_settings)

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        store = SessionChatOptionsStore(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        editSessionTitle = findViewById(R.id.editSessionTitle)
        formModule = ChatSettingsFormModule(this, findViewById(R.id.chatSettingsRoot))
        val options = store.get(sessionId)
        formModule.setOptions(options)
        editSessionTitle?.setText(options.sessionTitle)
        draftAvatarImage = options.sessionAvatarImageBase64

        // 取绑定的助手作为头像回退源
        val assistantId = SessionAssistantBindingStore(this).getAssistantId(sessionId)
        boundAssistant = if (assistantId.isNotEmpty()) MyAssistantStore(this).getById(assistantId) else null

        // writer 类型的会话显示大纲提示词区域
        val isWriter = boundAssistant.mode() == SessionMode.WRITER
        formModule.setOutlinePromptVisible(isWriter)

        imageAvatarPreview = findViewById(R.id.imageSessionAvatarPreview)
        textAvatarPreview = findViewById(R.id.textSessionAvatarPreview)
        btnClearAvatarImage = findViewById(R.id.btnClearSessionAvatarImage)
        val pickerContainer: View? = findViewById(R.id.sessionAvatarPickerContainer)

        imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            handlePickedImage(uri)
        }
        pickerContainer?.setOnClickListener { imagePickerLauncher.launch("image/*") }
        btnClearAvatarImage?.setOnClickListener {
            draftAvatarImage = ""
            refreshAvatarPreview()
        }

        refreshAvatarPreview()

        // Ink 建书设置 (writer 模式才显示)
        bindInkSettings(isWriter, options)

        val btnSave = findViewById<MaterialButton>(R.id.btnSaveSettings)
        btnSave.setOnClickListener {
            val out = formModule.collect()
            out.sessionTitle = editSessionTitle?.text?.toString()?.trim() ?: ""
            out.sessionAvatar = ""
            out.sessionAvatarImageBase64 = draftAvatarImage
            out.inkosSubtype = draftInkSubtype
            out.inkosBookRulesYaml = editInkBookRulesYaml?.text?.toString()?.trim().orEmpty()
            // 章数 / 字数: 不合法或空时退回默认 30/5000, 避免 inkos 端收到 0 之类的值
            out.inkosTargetChapters = editInkTargetChapters?.text?.toString()?.trim()?.toIntOrNull()
                ?.coerceIn(1, 1000) ?: 30
            out.inkosChapterWordCount = editInkChapterWordCount?.text?.toString()?.trim()?.toIntOrNull()
                ?.coerceIn(500, 20000) ?: 5000
            store.save(sessionId, out)
            finish()
        }
    }

    // ── Ink 建书设置 ─────────────────────────────────────────────

    private var draftInkSubtype: String = InkosSubtypePresets.DEFAULT.id
    private var editInkBookRulesYaml: TextInputEditText? = null
    private var editInkTargetChapters: TextInputEditText? = null
    private var editInkChapterWordCount: TextInputEditText? = null
    private var textInkSubtypeValue: TextView? = null

    private fun bindInkSettings(isWriter: Boolean, options: SessionChatOptions) {
        val section = findViewById<View>(R.id.sectionInkSettings) ?: return
        if (!isWriter) {
            section.visibility = View.GONE
            return
        }
        section.visibility = View.VISIBLE

        textInkSubtypeValue = findViewById(R.id.textInkSubtypeValue)
        editInkBookRulesYaml = findViewById(R.id.editInkBookRulesYaml)
        editInkTargetChapters = findViewById(R.id.editInkTargetChapters)
        editInkChapterWordCount = findViewById(R.id.editInkChapterWordCount)
        val subtypeRow = findViewById<View>(R.id.inkSubtypeRow)
        val btnReset = findViewById<MaterialButton>(R.id.btnResetInkBookRules)

        draftInkSubtype = options.inkosSubtype.ifEmpty { InkosSubtypePresets.DEFAULT.id }
        applyInkSubtypeLabel()

        val initialYaml = options.inkosBookRulesYaml.ifEmpty {
            InkosSubtypePresets.byId(draftInkSubtype).defaultBookRulesYaml
        }
        editInkBookRulesYaml?.setText(initialYaml)
        editInkTargetChapters?.setText(options.inkosTargetChapters.toString())
        editInkChapterWordCount?.setText(options.inkosChapterWordCount.toString())

        subtypeRow.setOnClickListener { showSubtypePicker() }
        btnReset.setOnClickListener {
            val preset = InkosSubtypePresets.byId(draftInkSubtype)
            editInkBookRulesYaml?.setText(preset.defaultBookRulesYaml)
            Toast.makeText(this, "已重置为 ${preset.displayName} 默认模板", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyInkSubtypeLabel() {
        textInkSubtypeValue?.text = InkosSubtypePresets.byId(draftInkSubtype).displayName
    }

    private fun showSubtypePicker() {
        val items = InkosSubtypePresets.ALL
        val labels = items.map { it.displayName }.toTypedArray()
        val currentIdx = items.indexOfFirst { it.id == draftInkSubtype }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("选择 Ink 子类预设")
            .setSingleChoiceItems(labels, currentIdx) { d, which ->
                val picked = items[which]
                // 如果用户当前 YAML 等于旧 preset 的默认 (没改过), 自动切到新 preset 默认
                val curYaml = editInkBookRulesYaml?.text?.toString().orEmpty().trim()
                val oldDefault = InkosSubtypePresets.byId(draftInkSubtype).defaultBookRulesYaml.trim()
                draftInkSubtype = picked.id
                applyInkSubtypeLabel()
                if (curYaml == oldDefault || curYaml.isEmpty()) {
                    editInkBookRulesYaml?.setText(picked.defaultBookRulesYaml)
                }
                d.dismiss()
            }
            .show()
    }

    private fun handlePickedImage(uri: Uri?) {
        if (uri == null) return
        val base64 = AvatarImageUtils.compressUriToBase64(contentResolver, uri, maxSize = 256, quality = 80)
        if (base64.isNullOrEmpty()) {
            Toast.makeText(this, "图片处理失败，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        draftAvatarImage = base64
        refreshAvatarPreview()
    }

    /**
     * 把当前 draft（image override + 文本 override）+ 助手回退合成一个虚拟 MyAssistant，喂给 helper 渲染。
     * 这样和首页 / 助手列表的头像渲染逻辑完全一致。
     */
    private fun refreshAvatarPreview() {
        val effective = MyAssistant().apply {
            avatarImageBase64 = if (draftAvatarImage.isNotEmpty()) draftAvatarImage
                else boundAssistant?.avatarImageBase64.orEmpty()
            avatar = if (draftAvatarImage.isNotEmpty()) "" else boundAssistant?.avatar.orEmpty()
            name = boundAssistant?.name.orEmpty()
        }
        val fallback = effective.name.ifEmpty {
            editSessionTitle?.text?.toString()?.trim().orEmpty()
        }
        AssistantAvatarHelper.bindAvatar(imageAvatarPreview, textAvatarPreview, effective, fallback)
        btnClearAvatarImage?.visibility = if (draftAvatarImage.isNotEmpty()) View.VISIBLE else View.GONE
    }
}
