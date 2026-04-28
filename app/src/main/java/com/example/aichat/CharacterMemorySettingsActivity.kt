package com.example.aichat

import android.os.Bundle
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class CharacterMemorySettingsActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_character_memory_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val store = CharacterMemoryConfigStore(this)

        val switchEnabled = findViewById<MaterialSwitch?>(R.id.switchCharacterMemoryEnabled)
        val editBaseUrl = findViewById<TextInputEditText?>(R.id.editCharacterMemoryBaseUrl)
        val editApiKey = findViewById<TextInputEditText?>(R.id.editCharacterMemoryApiKey)
        val editConnectTimeout = findViewById<TextInputEditText?>(R.id.editCharacterMemoryConnectTimeout)
        val editReadTimeout = findViewById<TextInputEditText?>(R.id.editCharacterMemoryReadTimeout)
        val switchDebug = findViewById<MaterialSwitch?>(R.id.switchCharacterMemoryDebug)

        switchEnabled?.isChecked = store.isEnabled()
        editBaseUrl?.setText(store.getBaseUrl())
        editApiKey?.setText(store.getApiKey())
        editConnectTimeout?.setText(store.getConnectTimeoutMs().toString())
        editReadTimeout?.setText(store.getReadTimeoutMs().toString())
        switchDebug?.isChecked = store.isDebugLogEnabled()

        val btnSave = findViewById<MaterialButton>(R.id.btnSaveCharacterMemorySettings)
        btnSave.setOnClickListener {
            val enabled = switchEnabled?.isChecked == true
            val debug = switchDebug?.isChecked == true
            val baseUrl = editBaseUrl?.text?.toString()?.trim() ?: ""
            val apiKey = editApiKey?.text?.toString()?.trim() ?: ""
            val connectTimeoutMs = parseIntOrDefault(
                editConnectTimeout?.text?.toString()?.trim() ?: "",
                store.getConnectTimeoutMs(),
            )
            val readTimeoutMs = parseIntOrDefault(
                editReadTimeout?.text?.toString()?.trim() ?: "",
                store.getReadTimeoutMs(),
            )
            store.saveAll(enabled, baseUrl, apiKey, connectTimeoutMs, readTimeoutMs, debug)
            Toast.makeText(this, R.string.character_memory_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun parseIntOrDefault(text: String?, fallback: Int): Int {
        if (text.isNullOrBlank()) return fallback
        return try {
            text.trim().toInt()
        } catch (_: Exception) {
            fallback
        }
    }
}
