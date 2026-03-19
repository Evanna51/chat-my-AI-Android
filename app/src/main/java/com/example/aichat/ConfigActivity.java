package com.example.aichat;

import android.os.Bundle;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class ConfigActivity extends ThemedActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        ConfigManager config = new ConfigManager(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextInputEditText editApiUrl = findViewById(R.id.editApiUrl);
        TextInputEditText editApiKey = findViewById(R.id.editApiKey);
        TextInputEditText editModel = findViewById(R.id.editModel);

        editApiUrl.setText(config.getApiUrl());
        editApiKey.setText(config.getApiKey());
        editModel.setText(config.getModel());

        MaterialButton btnSave = findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> {
            config.save(
                    editApiUrl.getText().toString().trim(),
                    editApiKey.getText().toString().trim(),
                    editModel.getText().toString().trim()
            );
            finish();
        });
    }
}
