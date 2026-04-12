package com.example.aichat

import android.os.Bundle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class ConfigActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        val config = ConfigManager(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val editApiUrl = findViewById<TextInputEditText>(R.id.editApiUrl)
        val editApiKey = findViewById<TextInputEditText>(R.id.editApiKey)
        val editModel = findViewById<TextInputEditText>(R.id.editModel)

        editApiUrl.setText(config.getApiUrl())
        editApiKey.setText(config.getApiKey())
        editModel.setText(config.getModel())

        val btnSave = findViewById<MaterialButton>(R.id.btnSave)
        btnSave.setOnClickListener {
            config.save(
                editApiUrl.text.toString().trim(),
                editApiKey.text.toString().trim(),
                editModel.text.toString().trim()
            )
            finish()
        }
    }
}
