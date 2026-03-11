package com.example.aichat;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;

/**
 * 可复用的聊天设置表单模块。
 */
public class ChatSettingsFormModule {

    private final Activity activity;
    private final View root;

    private final TextView textModelValue;
    private final View btnPickModel;
    private final TextInputEditText editSystemPrompt;
    private final TextInputEditText editTemperature;
    private final TextInputEditText editTopP;
    private final Slider sliderContextCount;
    private final TextView textContextCountValue;
    private final MaterialSwitch switchStream;
    private final MaterialSwitch switchThinking;
    private final TextInputLayout layoutGoogleThinkingBudget;
    private final TextInputEditText editGoogleThinkingBudget;

    private SessionChatOptions current = new SessionChatOptions();

    public ChatSettingsFormModule(Activity activity, View root) {
        this.activity = activity;
        this.root = root;
        this.textModelValue = root.findViewById(R.id.textModelValue);
        this.btnPickModel = root.findViewById(R.id.btnPickModel);
        this.editSystemPrompt = root.findViewById(R.id.editSystemPrompt);
        this.editTemperature = root.findViewById(R.id.editTemperature);
        this.editTopP = root.findViewById(R.id.editTopP);
        this.sliderContextCount = root.findViewById(R.id.sliderContextCount);
        this.textContextCountValue = root.findViewById(R.id.textContextCountValue);
        this.switchStream = root.findViewById(R.id.switchStream);
        this.switchThinking = root.findViewById(R.id.switchThinking);
        this.layoutGoogleThinkingBudget = root.findViewById(R.id.layoutGoogleThinkingBudget);
        this.editGoogleThinkingBudget = root.findViewById(R.id.editGoogleThinkingBudget);
        if (sliderContextCount != null) {
            sliderContextCount.setValueFrom(0f);
            sliderContextCount.setValueTo(100f);
            sliderContextCount.setStepSize(1f);
            sliderContextCount.addOnChangeListener((slider, value, fromUser) -> updateContextCountValue(Math.round(value)));
        }

        if (btnPickModel != null) {
            btnPickModel.setOnClickListener(v -> showModelPicker());
        }
        if (switchThinking != null) {
            switchThinking.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (layoutGoogleThinkingBudget != null) {
                    layoutGoogleThinkingBudget.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }
            });
        }
    }

    public void setOptions(SessionChatOptions options) {
        current = options != null ? options : new SessionChatOptions();
        updateModelText();
        if (editSystemPrompt != null) editSystemPrompt.setText(current.systemPrompt);
        if (editTemperature != null) editTemperature.setText(String.valueOf(current.temperature));
        if (editTopP != null) editTopP.setText(String.valueOf(current.topP));
        if (sliderContextCount != null) {
            int count = Math.max(0, Math.min(100, current.contextMessageCount));
            sliderContextCount.setValue((float) count);
            updateContextCountValue(count);
        }
        if (switchStream != null) switchStream.setChecked(current.streamOutput);
        if (switchThinking != null) switchThinking.setChecked(current.thinking);
        if (editGoogleThinkingBudget != null) {
            editGoogleThinkingBudget.setText(String.valueOf(current.googleThinkingBudget > 0 ? current.googleThinkingBudget : 1024));
        }
        if (layoutGoogleThinkingBudget != null) {
            layoutGoogleThinkingBudget.setVisibility(current.thinking ? View.VISIBLE : View.GONE);
        }
    }

    public SessionChatOptions collect() {
        SessionChatOptions out = new SessionChatOptions();
        out.modelKey = current.modelKey != null ? current.modelKey : "";
        out.systemPrompt = editSystemPrompt != null && editSystemPrompt.getText() != null
                ? editSystemPrompt.getText().toString().trim() : "";
        // Stop sequence is hidden in UI; preserve existing value for compatibility.
        out.stop = current.stop != null ? current.stop : "";
        out.temperature = parseFloat(editTemperature, 0.7f);
        out.topP = parseFloat(editTopP, 1.0f);
        out.contextMessageCount = getContextCount();
        out.streamOutput = switchStream != null && switchStream.isChecked();
        out.thinking = switchThinking != null && switchThinking.isChecked();
        out.googleThinkingBudget = parseInt(editGoogleThinkingBudget, 1024);
        return out;
    }

    private int getContextCount() {
        if (sliderContextCount == null) return 6;
        int count = Math.round(sliderContextCount.getValue());
        return Math.max(0, Math.min(100, count));
    }

    private void updateContextCountValue(int value) {
        if (textContextCountValue != null) {
            textContextCountValue.setText(String.valueOf(value));
        }
    }

    private float parseFloat(TextInputEditText edit, float def) {
        try {
            if (edit == null || edit.getText() == null) return def;
            String s = edit.getText().toString().trim();
            if (s.isEmpty()) return def;
            return Float.parseFloat(s);
        } catch (Exception e) {
            return def;
        }
    }

    private int parseInt(TextInputEditText edit, int def) {
        try {
            if (edit == null || edit.getText() == null) return def;
            String s = edit.getText().toString().trim();
            if (s.isEmpty()) return def;
            int v = Integer.parseInt(s);
            return Math.max(v, 0);
        } catch (Exception e) {
            return def;
        }
    }

    private void updateModelText() {
        if (textModelValue == null) return;
        if (current.modelKey == null || current.modelKey.isEmpty()) {
            textModelValue.setText("请选择模型");
            return;
        }
        ConfiguredModelPicker.Option option = ConfiguredModelPicker.Option.fromStorageKey(current.modelKey, activity);
        if (option != null) {
            textModelValue.setText(option.displayName + " (" + option.providerName + ")");
        } else {
            textModelValue.setText(current.modelKey);
        }
    }

    private void showModelPicker() {
        List<ConfiguredModelPicker.Option> options = ConfiguredModelPicker.getConfiguredModels(activity);
        if (options == null || options.isEmpty()) {
            new MaterialAlertDialogBuilder(activity)
                    .setMessage("请先在「模型管理」中添加厂商并添加模型")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_model_picker, null);
        RecyclerView recycler = dialogView.findViewById(R.id.recyclerOptions);
        recycler.setLayoutManager(new LinearLayoutManager(activity));

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("选择模型")
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        ModelPickerAdapter adapter = new ModelPickerAdapter(options, current.modelKey, option -> {
            current.modelKey = option.getStorageKey();
            updateModelText();
            dialog.dismiss();
        });
        recycler.setAdapter(adapter);
        dialog.show();
    }
}
