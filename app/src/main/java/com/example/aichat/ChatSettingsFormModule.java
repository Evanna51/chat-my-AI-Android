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
    private static final float[] CONTEXT_SLIDER_POSITIONS = buildContextSliderPositions();
    private static final int[] CONTEXT_SLIDER_VALUES = buildContextSliderValues();

    private final Activity activity;
    private final View root;

    private final TextView textModelValue;
    private final View btnPickModel;
    private final TextInputEditText editSystemPrompt;
    private final TextInputEditText editTemperature;
    private final TextInputEditText editTopP;
    private final Slider sliderContextCount;
    private final TextView textContextCountValue;
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
        this.switchThinking = root.findViewById(R.id.switchThinking);
        this.layoutGoogleThinkingBudget = root.findViewById(R.id.layoutGoogleThinkingBudget);
        this.editGoogleThinkingBudget = root.findViewById(R.id.editGoogleThinkingBudget);
        FormInputScrollHelper.enableFor(editSystemPrompt);
        if (sliderContextCount != null) {
            sliderContextCount.setValueFrom(0f);
            sliderContextCount.setValueTo(100f);
            sliderContextCount.setStepSize(0f);
            sliderContextCount.setLabelFormatter(value ->
                    String.valueOf(mapSliderPositionToContextValue(value)));
            sliderContextCount.addOnChangeListener((slider, value, fromUser) ->
                    updateContextCountValue(mapSliderPositionToContextValue(value)));
            sliderContextCount.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                @Override
                public void onStartTrackingTouch(Slider slider) {}

                @Override
                public void onStopTrackingTouch(Slider slider) {
                    if (slider == null) return;
                    float snapped = snapSliderPosition(slider.getValue());
                    if (Math.abs(snapped - slider.getValue()) > 0.0001f) {
                        slider.setValue(snapped);
                    }
                    updateContextCountValue(mapSliderPositionToContextValue(snapped));
                }
            });
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
            int count = current.contextMessageCount;
            float position = mapContextValueToSliderPosition(count);
            sliderContextCount.setValue(position);
            updateContextCountValue(mapSliderPositionToContextValue(position));
        }
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
        out.streamOutput = true;
        out.thinking = switchThinking != null && switchThinking.isChecked();
        out.googleThinkingBudget = parseInt(editGoogleThinkingBudget, 1024);
        return out;
    }

    private int getContextCount() {
        if (sliderContextCount == null) return 6;
        return mapSliderPositionToContextValue(sliderContextCount.getValue());
    }

    private void updateContextCountValue(int value) {
        if (textContextCountValue != null) {
            textContextCountValue.setText(String.valueOf(value));
        }
    }

    private float snapSliderPosition(float raw) {
        int nearest = 0;
        float minDiff = Float.MAX_VALUE;
        for (int i = 0; i < CONTEXT_SLIDER_POSITIONS.length; i++) {
            float diff = Math.abs(raw - CONTEXT_SLIDER_POSITIONS[i]);
            if (diff < minDiff) {
                minDiff = diff;
                nearest = i;
            }
        }
        return CONTEXT_SLIDER_POSITIONS[nearest];
    }

    private int mapSliderPositionToContextValue(float position) {
        int nearest = 0;
        float minDiff = Float.MAX_VALUE;
        for (int i = 0; i < CONTEXT_SLIDER_POSITIONS.length; i++) {
            float diff = Math.abs(position - CONTEXT_SLIDER_POSITIONS[i]);
            if (diff < minDiff) {
                minDiff = diff;
                nearest = i;
            }
        }
        return CONTEXT_SLIDER_VALUES[nearest];
    }

    private float mapContextValueToSliderPosition(int contextValue) {
        int nearest = 0;
        int minDiff = Integer.MAX_VALUE;
        for (int i = 0; i < CONTEXT_SLIDER_VALUES.length; i++) {
            int diff = Math.abs(contextValue - CONTEXT_SLIDER_VALUES[i]);
            if (diff < minDiff) {
                minDiff = diff;
                nearest = i;
            }
        }
        return CONTEXT_SLIDER_POSITIONS[nearest];
    }

    private static int[] buildContextSliderValues() {
        int[] values = new int[26];
        for (int i = 0; i <= 10; i++) {
            values[i] = i;
        }
        for (int i = 0; i < 15; i++) {
            values[11 + i] = 16 + i * 8; // 16..128
        }
        return values;
    }

    private static float[] buildContextSliderPositions() {
        float[] positions = new float[26];
        for (int i = 0; i <= 10; i++) {
            positions[i] = i * 6f; // 0..60
        }
        float step = 40f / 14f; // 60..100 split into 15 nodes
        for (int i = 0; i < 15; i++) {
            positions[11 + i] = 60f + i * step;
        }
        return positions;
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
