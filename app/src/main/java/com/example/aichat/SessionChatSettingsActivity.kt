package com.example.aichat

import android.os.Bundle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class SessionChatSettingsActivity : ThemedActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }

    private var sessionId: String? = null
    private lateinit var store: SessionChatOptionsStore
    private lateinit var formModule: ChatSettingsFormModule
    private var editSessionTitle: TextInputEditText? = null
    private var editSessionAvatar: TextInputEditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_chat_settings)

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        store = SessionChatOptionsStore(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        editSessionTitle = findViewById(R.id.editSessionTitle)
        editSessionAvatar = findViewById(R.id.editSessionAvatar)
        formModule = ChatSettingsFormModule(this, findViewById(R.id.chatSettingsRoot))
        val options = store.get(sessionId)
        formModule.setOptions(options)
        editSessionTitle?.setText(options.sessionTitle)
        editSessionAvatar?.setText(options.sessionAvatar)

        val btnSave = findViewById<MaterialButton>(R.id.btnSaveSettings)
        btnSave.setOnClickListener {
            val out = formModule.collect()
            out.sessionTitle = editSessionTitle?.text?.toString()?.trim() ?: ""
            out.sessionAvatar = editSessionAvatar?.text?.toString()?.trim() ?: ""
            store.save(sessionId, out)
            finish()
        }
    }
}
