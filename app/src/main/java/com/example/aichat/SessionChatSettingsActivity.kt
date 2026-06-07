package com.example.aichat

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
import com.example.aichat.session.SessionMode
import com.example.aichat.session.mode

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

        val btnSave = findViewById<MaterialButton>(R.id.btnSaveSettings)
        btnSave.setOnClickListener {
            val out = formModule.collect()
            out.sessionTitle = editSessionTitle?.text?.toString()?.trim() ?: ""
            out.sessionAvatar = ""
            out.sessionAvatarImageBase64 = draftAvatarImage
            store.save(sessionId, out)
            finish()
        }
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
