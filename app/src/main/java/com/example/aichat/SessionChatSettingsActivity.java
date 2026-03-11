package com.example.aichat;

import android.os.Bundle;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class SessionChatSettingsActivity extends ThemedActivity {
    public static final String EXTRA_SESSION_ID = "session_id";

    private String sessionId;
    private SessionChatOptionsStore store;
    private ChatSettingsFormModule formModule;
    private TextInputEditText editSessionTitle;
    private TextInputEditText editSessionAvatar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_chat_settings);

        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        store = new SessionChatOptionsStore(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        editSessionTitle = findViewById(R.id.editSessionTitle);
        editSessionAvatar = findViewById(R.id.editSessionAvatar);
        formModule = new ChatSettingsFormModule(this, findViewById(R.id.chatSettingsRoot));
        SessionChatOptions options = store.get(sessionId);
        formModule.setOptions(options);
        if (editSessionTitle != null) editSessionTitle.setText(options.sessionTitle);
        if (editSessionAvatar != null) editSessionAvatar.setText(options.sessionAvatar);

        MaterialButton btnSave = findViewById(R.id.btnSaveSettings);
        btnSave.setOnClickListener(v -> {
            SessionChatOptions out = formModule.collect();
            out.sessionTitle = editSessionTitle != null && editSessionTitle.getText() != null
                    ? editSessionTitle.getText().toString().trim() : "";
            out.sessionAvatar = editSessionAvatar != null && editSessionAvatar.getText() != null
                    ? editSessionAvatar.getText().toString().trim() : "";
            store.save(sessionId, out);
            finish();
        });
    }
}
