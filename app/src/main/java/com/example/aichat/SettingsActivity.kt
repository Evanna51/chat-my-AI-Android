package com.example.aichat

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : ThemedActivity() {

    private var providerAdapter: ProviderListAdapter? = null
    private var providerManager: ProviderManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        setupGeneralSettings()

        findViewById<View>(R.id.cardModelConfig).setOnClickListener {
            startActivity(Intent(this, ModelConfigActivity::class.java))
        }
        val cardCharacterMemory = findViewById<View?>(R.id.cardCharacterMemory)
        cardCharacterMemory?.setOnClickListener { showCharacterMemorySettingsDialog() }

        val includeModel = findViewById<View?>(R.id.includeModelManagement)
        if (includeModel != null) {
            setupModelManagement(includeModel)
        }
    }

    private fun setupGeneralSettings() {
        val config = ConfigManager(this)
        val header = findViewById<View>(R.id.headerGeneral)
        val expand = findViewById<View>(R.id.expandGeneral)
        val icon = findViewById<ImageView?>(R.id.iconGeneralExpand)

        header.setOnClickListener {
            val visible = expand.visibility == View.VISIBLE
            expand.visibility = if (visible) View.GONE else View.VISIBLE
            icon?.rotation = if (visible) 0f else 90f
        }

        val radioTheme = findViewById<RadioGroup?>(R.id.radioTheme)
        radioTheme?.let { rg ->
            val t = config.getTheme()
            val id = when (t) {
                "light" -> R.id.themeLight
                "dark" -> R.id.themeDark
                else -> R.id.themeSystem
            }
            rg.check(id)
            rg.setOnCheckedChangeListener { _, checkedId ->
                val theme = when (checkedId) {
                    R.id.themeLight -> "light"
                    R.id.themeDark -> "dark"
                    else -> "system"
                }
                config.setTheme(theme)
                applyTheme(theme)
            }
        }

        val radioColor = findViewById<RadioGroup?>(R.id.radioThemeColor)
        radioColor?.let { rg ->
            val c = config.getThemeColor()
            val id = when (c) {
                "green" -> R.id.colorGreen
                "purple" -> R.id.colorPurple
                "orange" -> R.id.colorOrange
                else -> R.id.colorBlue
            }
            rg.check(id)
            rg.setOnCheckedChangeListener { _, checkedId ->
                val color = when (checkedId) {
                    R.id.colorGreen -> "green"
                    R.id.colorPurple -> "purple"
                    R.id.colorOrange -> "orange"
                    else -> "blue"
                }
                config.setThemeColor(color)
                recreate()
            }
        }

        val seekFont = findViewById<SeekBar?>(R.id.seekFontSize)
        val textFontValue = findViewById<TextView?>(R.id.textFontSizeValue)
        val sizes = intArrayOf(12, 14, 16, 18, 20)
        if (seekFont != null && textFontValue != null) {
            val fs = config.getFontSize()
            var idx = 1
            val oldIdx = intArrayOf(idx)
            for (i in sizes.indices) if (sizes[i] == fs) { idx = i; break }
            oldIdx[0] = idx
            seekFont.progress = idx
            textFontValue.text = sizes[idx].toString()
            seekFont.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val v = sizes[minOf(progress, sizes.size - 1)]
                        textFontValue.text = v.toString()
                        config.setFontSize(v)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    val current = minOf(s.progress, sizes.size - 1)
                    if (current != oldIdx[0]) {
                        oldIdx[0] = current
                        recreate()
                    }
                }
            })
        }

        findViewById<View>(R.id.btnNewChat).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("action", "new_chat")
            )
            finish()
        }
        findViewById<View>(R.id.btnExport).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("action", "export")
            )
            finish()
        }
    }

    private fun applyTheme(theme: String) {
        val mode = when (theme) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun setupModelManagement(root: View) {
        providerManager = ProviderManager(this)

        val btnAdd = root.findViewById<View?>(R.id.btnAddProvider)
        btnAdd?.setOnClickListener { showAddProviderDialog() }

        val recyclerProviders = root.findViewById<RecyclerView?>(R.id.recyclerProviders)
        recyclerProviders?.let { rv ->
            rv.layoutManager = LinearLayoutManager(this)
            rv.isNestedScrollingEnabled = false
            providerAdapter = ProviderListAdapter()
            providerAdapter!!.setOnProviderClickListener { p ->
                val i = Intent(this, ProviderDetailActivity::class.java)
                i.putExtra(ProviderDetailActivity.EXTRA_PROVIDER_ID, p.id)
                startActivity(i)
            }
            rv.adapter = providerAdapter
        }

        refreshProviders()
    }

    private fun showAddProviderDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_provider_select, null)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.recyclerCatalog)
        recycler.layoutManager = LinearLayoutManager(this)
        val catalogAdapter = ProviderCatalogAdapter()
        val enabled = HashSet<String>()
        for (p in providerManager!!.getAllProviders()) {
            enabled.add(p.id)
        }
        catalogAdapter.setData(ProviderCatalog.getAll(), enabled)
        val d = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        catalogAdapter.setOnItemClickListener { item ->
            if (!providerManager!!.isEnabled(item.id)) {
                providerManager!!.addPresetProvider(item.id)
                refreshProviders()
            }
            d.dismiss()
            startActivity(
                Intent(this, ProviderDetailActivity::class.java)
                    .putExtra(ProviderDetailActivity.EXTRA_PROVIDER_ID, item.id)
            )
        }
        recycler.adapter = catalogAdapter
        d.show()
    }

    private fun refreshProviders() {
        providerAdapter?.let { adapter ->
            val list = providerManager?.getAllProviders() ?: emptyList()
            adapter.setProviders(list)
        }
    }

    private fun showCharacterMemorySettingsDialog() {
        val store = CharacterMemoryConfigStore(this)
        val view = layoutInflater.inflate(R.layout.dialog_character_memory_settings, null)
        val switchEnabled = view.findViewById<MaterialSwitch?>(R.id.switchCharacterMemoryEnabled)
        val editBaseUrl = view.findViewById<TextInputEditText?>(R.id.editCharacterMemoryBaseUrl)
        val editApiKey = view.findViewById<TextInputEditText?>(R.id.editCharacterMemoryApiKey)
        val editConnectTimeout = view.findViewById<TextInputEditText?>(R.id.editCharacterMemoryConnectTimeout)
        val editReadTimeout = view.findViewById<TextInputEditText?>(R.id.editCharacterMemoryReadTimeout)
        val switchDebug = view.findViewById<MaterialSwitch?>(R.id.switchCharacterMemoryDebug)
        switchEnabled?.isChecked = store.isEnabled()
        editBaseUrl?.setText(store.getBaseUrl())
        editApiKey?.setText(store.getApiKey())
        editConnectTimeout?.setText(store.getConnectTimeoutMs().toString())
        editReadTimeout?.setText(store.getReadTimeoutMs().toString())
        switchDebug?.isChecked = store.isDebugLogEnabled()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.character_memory_settings)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val enabled = switchEnabled?.isChecked == true
                val debug = switchDebug?.isChecked == true
                val baseUrl = editBaseUrl?.text?.toString()?.trim() ?: ""
                val apiKey = editApiKey?.text?.toString()?.trim() ?: ""
                val connectTimeoutMs = parseIntOrDefault(
                    editConnectTimeout?.text?.toString()?.trim() ?: "",
                    store.getConnectTimeoutMs()
                )
                val readTimeoutMs = parseIntOrDefault(
                    editReadTimeout?.text?.toString()?.trim() ?: "",
                    store.getReadTimeoutMs()
                )
                store.saveAll(enabled, baseUrl, apiKey, connectTimeoutMs, readTimeoutMs, debug)
                Toast.makeText(this, R.string.character_memory_saved, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun parseIntOrDefault(text: String?, fallback: Int): Int {
        if (text.isNullOrBlank()) return fallback
        return try {
            text.trim().toInt()
        } catch (e: Exception) {
            fallback
        }
    }

    override fun onResume() {
        super.onResume()
        refreshProviders()
    }
}
