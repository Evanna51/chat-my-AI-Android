package com.example.aichat;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ProviderDetailActivity extends ThemedActivity {

    public static final String EXTRA_PROVIDER_ID = "provider_id";
    private String providerId;
    private ProviderInfo provider;
    private ProviderManager manager;
    private ProviderCatalog.CatalogItem catalog;
    private TextInputLayout layoutApiHost, layoutApiPath, layoutApiKey;
    private MaterialButton btnFetch;
    private TextInputEditText editApiHost, editApiPath, editApiKey;
    private AddedModelAdapter addedAdapter;
    private AvailableModelAdapter availableAdapter;
    private List<ProviderInfo.ProviderModelInfo> availableModels = new ArrayList<>();
    private View textAvailableTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_provider_detail);

        providerId = getIntent().getStringExtra(EXTRA_PROVIDER_ID);
        manager = new ProviderManager(this);
        catalog = ProviderCatalog.get(providerId);
        provider = manager.getProvider(providerId);
        if (provider == null || catalog == null) {
            finish();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(provider.name);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> saveAndFinish());

        layoutApiHost = findViewById(R.id.layoutApiHost);
        layoutApiPath = findViewById(R.id.layoutApiPath);
        layoutApiKey = findViewById(R.id.layoutApiKey);
        editApiHost = findViewById(R.id.editApiHost);
        editApiPath = findViewById(R.id.editApiPath);
        editApiKey = findViewById(R.id.editApiKey);

        boolean editableEndpoint = "custom".equals(providerId)
                || ProviderCatalog.CATEGORY_LOCAL.equals(catalog.category);
        if (editableEndpoint) {
            layoutApiHost.setVisibility(View.VISIBLE);
            layoutApiPath.setVisibility(View.VISIBLE);
        }
        if (catalog.needsKey) {
            layoutApiKey.setVisibility(View.VISIBLE);
        } else {
            layoutApiKey.setVisibility(View.GONE);
        }

        editApiHost.setText(provider.apiHost != null ? provider.apiHost : catalog.apiHost);
        editApiPath.setText(provider.apiPath != null && !provider.apiPath.isEmpty() ? provider.apiPath : catalog.apiPath);
        if (editApiPath.getText().toString().isEmpty()) editApiPath.setText("/chat/completions");
        editApiKey.setText(provider.apiKey != null ? provider.apiKey : "");

        RecyclerView recyclerAdded = findViewById(R.id.recyclerModelsAdded);
        recyclerAdded.setLayoutManager(new LinearLayoutManager(this));
        recyclerAdded.setNestedScrollingEnabled(false);
        addedAdapter = new AddedModelAdapter();
        addedAdapter.setModels(provider.models);
        addedAdapter.setOnModelActionListener(new AddedModelAdapter.OnModelActionListener() {
            @Override
            public void onEditAlias(ProviderInfo.ProviderModelInfo model) {
                showAliasDialog(model, true);
            }

            @Override
            public void onRemove(ProviderInfo.ProviderModelInfo model) {
                provider.models.remove(model);
                addedAdapter.setModels(provider.models);
                refreshAvailableAdapter();
            }
        });
        recyclerAdded.setAdapter(addedAdapter);

        textAvailableTitle = findViewById(R.id.textAvailableTitle);
        RecyclerView recyclerAvailable = findViewById(R.id.recyclerModelsAvailable);
        recyclerAvailable.setLayoutManager(new LinearLayoutManager(this));
        recyclerAvailable.setNestedScrollingEnabled(true);
        availableAdapter = new AvailableModelAdapter();
        availableAdapter.setOnAddListener(m -> showAliasDialog(m, false));
        recyclerAvailable.setAdapter(availableAdapter);

        btnFetch = findViewById(R.id.btnFetchModels);
        btnFetch.setOnClickListener(v -> fetchModels());

        MaterialButton btnDelete = findViewById(R.id.btnDeleteProvider);
        btnDelete.setVisibility(View.VISIBLE);
        btnDelete.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("删除厂商")
                .setMessage("确定删除 " + provider.name + " 吗？")
                .setPositiveButton("删除", (d, w) -> {
                    manager.removeProvider(providerId);
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    @Override
    public void onBackPressed() {
        saveAndFinish();
    }

    private void saveAndFinish() {
        ProviderManager.ProviderSettings s = new ProviderManager.ProviderSettings();
        s.apiHost = isLayoutVisible(layoutApiHost) ? (editApiHost != null ? editApiHost.getText().toString().trim() : "") : catalog.apiHost;
        s.apiPath = isLayoutVisible(layoutApiPath) ? (editApiPath != null ? editApiPath.getText().toString().trim() : "/chat/completions") : catalog.apiPath;
        s.apiKey = editApiKey != null ? editApiKey.getText().toString().trim() : "";
        s.models = provider.models != null ? provider.models : new ArrayList<>();
        manager.updateProviderSettings(providerId, s);
        finish();
    }

    private boolean isLayoutVisible(TextInputLayout layout) {
        return layout != null && layout.getVisibility() == View.VISIBLE;
    }

    private void refreshAvailableAdapter() {
        Set<String> addedIds = new HashSet<>();
        if (provider.models != null) {
            for (ProviderInfo.ProviderModelInfo m : provider.models) {
                if (m.modelId != null) addedIds.add(m.modelId);
            }
        }
        availableAdapter.setItems(availableModels, addedIds);
    }

    private void showAliasDialog(ProviderInfo.ProviderModelInfo model, boolean isEdit) {
        View view = getLayoutInflater().inflate(R.layout.dialog_model_alias, null);
        TextInputEditText editAlias = view.findViewById(R.id.editAlias);
        view.<android.widget.TextView>findViewById(R.id.textModelId).setText("模型: " + model.modelId);
        if (isEdit && model.nickname != null) {
            editAlias.setText(model.nickname);
        }
        new MaterialAlertDialogBuilder(this)
                .setView(view)
                .setTitle(isEdit ? "编辑别名" : "添加模型")
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String alias = editAlias.getText().toString().trim();
                    if (!isEdit) {
                        ProviderInfo.ProviderModelInfo added = new ProviderInfo.ProviderModelInfo(model.modelId);
                        added.nickname = alias.isEmpty() ? null : alias;
                        provider.models.add(added);
                        addedAdapter.setModels(provider.models);
                        refreshAvailableAdapter();
                        Toast.makeText(this, "已添加 " + model.modelId, Toast.LENGTH_SHORT).show();
                    } else {
                        model.nickname = alias.isEmpty() ? null : alias;
                        addedAdapter.setModels(provider.models);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void fetchModels() {
        String host = (editApiHost != null ? editApiHost.getText().toString().trim() : "").isEmpty() ? catalog.apiHost : editApiHost.getText().toString().trim();
        String path = (editApiPath != null ? editApiPath.getText().toString().trim() : "").isEmpty() ? catalog.apiPath : editApiPath.getText().toString().trim();
        String key = editApiKey != null ? editApiKey.getText().toString().trim() : "";
        if (catalog.needsKey && key.isEmpty()) {
            Toast.makeText(this, "请填写 API Key", Toast.LENGTH_SHORT).show();
            return;
        }
        if (host.isEmpty()) {
            Toast.makeText(this, "请填写 API Host", Toast.LENGTH_SHORT).show();
            return;
        }
        btnFetch.setEnabled(false);
        ModelsFetcher.fetch(host, path, key, new ModelsFetcher.Callback() {
            @Override
            public void onSuccess(List<ProviderInfo.ProviderModelInfo> models) {
                runOnUiThread(() -> {
                    btnFetch.setEnabled(true);
                    availableModels = models != null ? models : new ArrayList<>();
                    textAvailableTitle.setVisibility(availableModels.isEmpty() ? View.GONE : View.VISIBLE);
                    findViewById(R.id.recyclerModelsAvailable).setVisibility(availableModels.isEmpty() ? View.GONE : View.VISIBLE);
                    refreshAvailableAdapter();
                    Toast.makeText(ProviderDetailActivity.this,
                            "获取到 " + (models != null ? models.size() : 0) + " 个模型，点击「添加」加入", Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    btnFetch.setEnabled(true);
                    Toast.makeText(ProviderDetailActivity.this, "获取失败: " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
