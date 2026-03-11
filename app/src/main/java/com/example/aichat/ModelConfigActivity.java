package com.example.aichat;

import android.content.res.TypedArray;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.List;

/** 模型配置：为各任务选用模型预设 */
public class ModelConfigActivity extends ThemedActivity {

    private ModelConfig modelConfig;
    private TextView textChatModel, textThreadNamingModel, textSearchModel, textSummaryModel;
    private String chatPreset, threadNamingPreset, searchPreset, summaryPreset;
    private SwitchMaterial switchHomeMode;
    private boolean editingHomeMode = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_config);

        modelConfig = new ModelConfig(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        textChatModel = findViewById(R.id.textChatModel);
        textThreadNamingModel = findViewById(R.id.textThreadNamingModel);
        textSearchModel = findViewById(R.id.textSearchModel);
        textSummaryModel = findViewById(R.id.textSummaryModel);
        switchHomeMode = findViewById(R.id.switchHomeMode);

        if (switchHomeMode != null) {
            editingHomeMode = modelConfig.isHomeModeEnabled();
            switchHomeMode.setChecked(editingHomeMode);
            switchHomeMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                editingHomeMode = isChecked;
                refreshDisplay();
            });
        }

        refreshDisplay();

        findViewById(R.id.cardChatModel).setOnClickListener(v -> showPicker(0));
        findViewById(R.id.cardThreadNamingModel).setOnClickListener(v -> showPicker(1));
        findViewById(R.id.cardSearchModel).setOnClickListener(v -> showPicker(2));
        findViewById(R.id.cardSummaryModel).setOnClickListener(v -> showPicker(3));

        MaterialButton btnSave = findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> {
            if (editingHomeMode) {
                modelConfig.setHomeChatPreset(chatPreset != null ? chatPreset : "");
                modelConfig.setHomeThreadNamingPreset(threadNamingPreset != null ? threadNamingPreset : "");
                modelConfig.setHomeSearchPreset(searchPreset != null ? searchPreset : "");
                modelConfig.setHomeSummaryPreset(summaryPreset != null ? summaryPreset : "");
            } else {
                modelConfig.setAwayChatPreset(chatPreset != null ? chatPreset : "");
                modelConfig.setAwayThreadNamingPreset(threadNamingPreset != null ? threadNamingPreset : "");
                modelConfig.setAwaySearchPreset(searchPreset != null ? searchPreset : "");
                modelConfig.setAwaySummaryPreset(summaryPreset != null ? summaryPreset : "");
            }
            modelConfig.setHomeModeEnabled(editingHomeMode);
            syncToConfigManager();
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDisplay();
    }

    private void refreshDisplay() {
        if (editingHomeMode) {
            chatPreset = modelConfig.getHomeChatPreset();
            threadNamingPreset = modelConfig.getHomeThreadNamingPreset();
            searchPreset = modelConfig.getHomeSearchPreset();
            summaryPreset = modelConfig.getHomeSummaryPreset();
        } else {
            chatPreset = modelConfig.getAwayChatPreset();
            threadNamingPreset = modelConfig.getAwayThreadNamingPreset();
            searchPreset = modelConfig.getAwaySearchPreset();
            summaryPreset = modelConfig.getAwaySummaryPreset();
        }
        updateText(textChatModel, chatPreset);
        updateText(textThreadNamingModel, threadNamingPreset);
        updateText(textSearchModel, searchPreset);
        updateText(textSummaryModel, summaryPreset);
    }

    private void updateText(TextView tv, String storageKey) {
        if (tv == null) return;
        if (storageKey == null || storageKey.isEmpty()) {
            tv.setText("请选择已配置的模型");
            tv.setTextColor(0xFF9E9E9E);
            return;
        }
        try {
            ConfiguredModelPicker.Option o = ConfiguredModelPicker.Option.fromStorageKey(storageKey, this);
            if (o != null && o.displayName != null && o.providerName != null) {
                tv.setText(o.displayName + " (" + o.providerName + ")");
                TypedArray a = tv.getContext().getTheme().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
                tv.setTextColor(a.getColor(0, 0xFF212121));
                a.recycle();
            } else {
                tv.setText(storageKey + "（厂商或模型已移除）");
                tv.setTextColor(0xFF9E9E9E);
            }
        } catch (Exception e) {
            tv.setText(storageKey);
            tv.setTextColor(0xFF9E9E9E);
        }
    }

    private void showPicker(int field) {
        List<ConfiguredModelPicker.Option> options = ConfiguredModelPicker.getConfiguredModels(this);
        if (options.isEmpty()) {
            Toast.makeText(this, "请先在「模型管理」中添加厂商并配置 API Key、获取并添加模型", Toast.LENGTH_LONG).show();
            return;
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_model_picker, null);
        RecyclerView recycler = dialogView.findViewById(R.id.recyclerOptions);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        String[] titles = {"对话选用", "话题命名选用", "搜索选用", "总结选用"};
        String currentKey = field == 0 ? chatPreset : field == 1 ? threadNamingPreset : field == 2 ? searchPreset : summaryPreset;

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(titles[field])
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        ModelPickerAdapter adapter = new ModelPickerAdapter(options, currentKey, option -> {
            if (field == 0) {
                chatPreset = option.getStorageKey();
                updateText(textChatModel, chatPreset);
            } else if (field == 1) {
                threadNamingPreset = option.getStorageKey();
                updateText(textThreadNamingModel, threadNamingPreset);
            } else if (field == 2) {
                searchPreset = option.getStorageKey();
                updateText(textSearchModel, searchPreset);
            } else {
                summaryPreset = option.getStorageKey();
                updateText(textSummaryModel, summaryPreset);
            }
            dialog.dismiss();
        });
        recycler.setAdapter(adapter);
        dialog.show();
    }

    private void syncToConfigManager() {
        ConfigManager cm = new ConfigManager(this);
        cm.setModel(modelConfig.getChatPreset());
        cm.setThreadNamingModel(modelConfig.getThreadNamingPreset());
        cm.setSearchModel(modelConfig.getSearchPreset());
        cm.setSummaryModel(modelConfig.getSummaryPreset());
    }
}
