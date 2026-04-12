package com.example.aichat

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.ArrayList
import java.util.HashSet

class ProviderDetailActivity : ThemedActivity() {

    companion object {
        const val EXTRA_PROVIDER_ID = "provider_id"
    }

    private var providerId: String? = null
    private lateinit var provider: ProviderInfo
    private lateinit var manager: ProviderManager
    private lateinit var catalog: ProviderCatalog.CatalogItem
    private lateinit var layoutApiHost: TextInputLayout
    private lateinit var layoutApiPath: TextInputLayout
    private lateinit var layoutApiKey: TextInputLayout
    private lateinit var btnFetch: MaterialButton
    private lateinit var editApiHost: TextInputEditText
    private lateinit var editApiPath: TextInputEditText
    private lateinit var editApiKey: TextInputEditText
    private lateinit var addedAdapter: AddedModelAdapter
    private lateinit var availableAdapter: AvailableModelAdapter
    private var availableModels: MutableList<ProviderInfo.ProviderModelInfo> = ArrayList()
    private var textAvailableTitle: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_provider_detail)

        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID)
        manager = ProviderManager(this)
        val catalogItem = providerId?.let { ProviderCatalog.get(it) }
        val providerInfo = providerId?.let { manager.getProvider(it) }
        if (providerInfo == null || catalogItem == null) {
            finish()
            return
        }
        catalog = catalogItem
        provider = providerInfo

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        toolbar.title = provider.name
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { saveAndFinish() }

        layoutApiHost = findViewById(R.id.layoutApiHost)
        layoutApiPath = findViewById(R.id.layoutApiPath)
        layoutApiKey = findViewById(R.id.layoutApiKey)
        editApiHost = findViewById(R.id.editApiHost)
        editApiPath = findViewById(R.id.editApiPath)
        editApiKey = findViewById(R.id.editApiKey)

        val editableEndpoint = "custom" == providerId
                || ProviderCatalog.CATEGORY_LOCAL == catalog.category
        if (editableEndpoint) {
            layoutApiHost.visibility = View.VISIBLE
            layoutApiPath.visibility = View.VISIBLE
        }
        if (catalog.needsKey) {
            layoutApiKey.visibility = View.VISIBLE
        } else {
            layoutApiKey.visibility = View.GONE
        }

        editApiHost.setText(provider.apiHost ?: catalog.apiHost)
        editApiPath.setText(
            if (provider.apiPath != null && provider.apiPath.isNotEmpty()) provider.apiPath
            else catalog.apiPath
        )
        if (editApiPath.text.toString().isEmpty()) editApiPath.setText("/chat/completions")
        editApiKey.setText(provider.apiKey ?: "")

        val recyclerAdded: RecyclerView = findViewById(R.id.recyclerModelsAdded)
        recyclerAdded.layoutManager = LinearLayoutManager(this)
        recyclerAdded.isNestedScrollingEnabled = false
        addedAdapter = AddedModelAdapter()
        addedAdapter.setModels(provider.models)
        addedAdapter.setOnModelActionListener(object : AddedModelAdapter.OnModelActionListener {
            override fun onEditAlias(model: ProviderInfo.ProviderModelInfo) {
                showAliasDialog(model, true)
            }

            override fun onRemove(model: ProviderInfo.ProviderModelInfo) {
                provider.models.remove(model)
                addedAdapter.setModels(provider.models)
                refreshAvailableAdapter()
            }
        })
        recyclerAdded.adapter = addedAdapter

        textAvailableTitle = findViewById(R.id.textAvailableTitle)
        val recyclerAvailable: RecyclerView = findViewById(R.id.recyclerModelsAvailable)
        recyclerAvailable.layoutManager = LinearLayoutManager(this)
        recyclerAvailable.isNestedScrollingEnabled = true
        availableAdapter = AvailableModelAdapter()
        availableAdapter.setOnAddListener { m -> showAliasDialog(m, false) }
        recyclerAvailable.adapter = availableAdapter

        btnFetch = findViewById(R.id.btnFetchModels)
        btnFetch.setOnClickListener { fetchModels() }

        val btnDelete: MaterialButton = findViewById(R.id.btnDeleteProvider)
        btnDelete.visibility = View.VISIBLE
        btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("删除厂商")
                .setMessage("确定删除 ${provider.name} 吗？")
                .setPositiveButton("删除") { _, _ ->
                    if (providerId != null) manager.removeProvider(providerId!!)
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        saveAndFinish()
    }

    private fun saveAndFinish() {
        val s = ProviderManager.ProviderSettings()
        s.apiHost = if (isLayoutVisible(layoutApiHost)) (editApiHost.text?.toString()?.trim() ?: "") else catalog.apiHost
        s.apiPath = if (isLayoutVisible(layoutApiPath)) (editApiPath.text?.toString()?.trim() ?: "/chat/completions") else catalog.apiPath
        s.apiKey = editApiKey.text?.toString()?.trim() ?: ""
        s.models = provider.models
        if (providerId != null) manager.updateProviderSettings(providerId!!, s)
        finish()
    }

    private fun isLayoutVisible(layout: TextInputLayout?): Boolean {
        return layout != null && layout.visibility == View.VISIBLE
    }

    private fun refreshAvailableAdapter() {
        val addedIds = HashSet<String>()
        provider.models.forEach { m ->
            addedIds.add(m.modelId)
        }
        availableAdapter.setItems(availableModels, addedIds)
    }

    private fun showAliasDialog(model: ProviderInfo.ProviderModelInfo, isEdit: Boolean) {
        val view = layoutInflater.inflate(R.layout.dialog_model_alias, null)
        val editAlias: TextInputEditText = view.findViewById(R.id.editAlias)
        view.findViewById<android.widget.TextView>(R.id.textModelId).text = "模型: ${model.modelId}"
        if (isEdit && model.nickname.isNotEmpty()) {
            editAlias.setText(model.nickname)
        }
        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setTitle(if (isEdit) "编辑别名" else "添加模型")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val alias = editAlias.text?.toString()?.trim() ?: ""
                if (!isEdit) {
                    val added = ProviderInfo.ProviderModelInfo(model.modelId)
                    added.nickname = alias
                    provider.models.add(added)
                    addedAdapter.setModels(provider.models)
                    refreshAvailableAdapter()
                    Toast.makeText(this, "已添加 ${model.modelId}", Toast.LENGTH_SHORT).show()
                } else {
                    model.nickname = alias
                    addedAdapter.setModels(provider.models)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fetchModels() {
        val hostInput = editApiHost.text?.toString()?.trim() ?: ""
        val host = if (hostInput.isEmpty()) catalog.apiHost else hostInput
        val pathInput = editApiPath.text?.toString()?.trim() ?: ""
        val path = if (pathInput.isEmpty()) catalog.apiPath else pathInput
        val key = editApiKey.text?.toString()?.trim() ?: ""
        if (catalog.needsKey && key.isEmpty()) {
            Toast.makeText(this, "请填写 API Key", Toast.LENGTH_SHORT).show()
            return
        }
        if (host.isEmpty()) {
            Toast.makeText(this, "请填写 API Host", Toast.LENGTH_SHORT).show()
            return
        }
        btnFetch.isEnabled = false
        ModelsFetcher.fetch(host, path, key, object : ModelsFetcher.Callback {
            override fun onSuccess(models: List<ProviderInfo.ProviderModelInfo>) {
                runOnUiThread {
                    btnFetch.isEnabled = true
                    availableModels = models.toMutableList()
                    textAvailableTitle?.visibility = if (availableModels.isEmpty()) View.GONE else View.VISIBLE
                    findViewById<View>(R.id.recyclerModelsAvailable)?.visibility =
                        if (availableModels.isEmpty()) View.GONE else View.VISIBLE
                    refreshAvailableAdapter()
                    Toast.makeText(
                        this@ProviderDetailActivity,
                        "获取到 ${models.size} 个模型，点击「添加」加入",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    btnFetch.isEnabled = true
                    Toast.makeText(this@ProviderDetailActivity, "获取失败: $message", Toast.LENGTH_LONG).show()
                }
            }
        })
    }
}
