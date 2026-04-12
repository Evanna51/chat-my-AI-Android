package com.example.aichat;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SettingsActivity extends ThemedActivity {

    private ProviderListAdapter providerAdapter;
    private ProviderManager providerManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        setupGeneralSettings();

        findViewById(R.id.cardModelConfig).setOnClickListener(v ->
                startActivity(new Intent(this, ModelConfigActivity.class)));
        View cardCharacterMemory = findViewById(R.id.cardCharacterMemory);
        if (cardCharacterMemory != null) {
            cardCharacterMemory.setOnClickListener(v -> showCharacterMemorySettingsDialog());
        }

        View includeModel = findViewById(R.id.includeModelManagement);
        if (includeModel != null) {
            setupModelManagement(includeModel);
        }
    }

    private void setupGeneralSettings() {
        ConfigManager config = new ConfigManager(this);
        View header = findViewById(R.id.headerGeneral);
        View expand = findViewById(R.id.expandGeneral);
        ImageView icon = findViewById(R.id.iconGeneralExpand);

        header.setOnClickListener(v -> {
            boolean visible = expand.getVisibility() == View.VISIBLE;
            expand.setVisibility(visible ? View.GONE : View.VISIBLE);
            if (icon != null) icon.setRotation(visible ? 0 : 90);
        });

        RadioGroup radioTheme = findViewById(R.id.radioTheme);
        if (radioTheme != null) {
            String t = config.getTheme();
            int id = "light".equals(t) ? R.id.themeLight : "dark".equals(t) ? R.id.themeDark : R.id.themeSystem;
            radioTheme.check(id);
            radioTheme.setOnCheckedChangeListener((g, checkedId) -> {
                String theme = checkedId == R.id.themeLight ? "light" : checkedId == R.id.themeDark ? "dark" : "system";
                config.setTheme(theme);
                applyTheme(theme);
            });
        }

        RadioGroup radioColor = findViewById(R.id.radioThemeColor);
        if (radioColor != null) {
            String c = config.getThemeColor();
            int id = "green".equals(c) ? R.id.colorGreen : "purple".equals(c) ? R.id.colorPurple : "orange".equals(c) ? R.id.colorOrange : R.id.colorBlue;
            radioColor.check(id);
            radioColor.setOnCheckedChangeListener((g, checkedId) -> {
                String color = checkedId == R.id.colorGreen ? "green" : checkedId == R.id.colorPurple ? "purple" : checkedId == R.id.colorOrange ? "orange" : "blue";
                config.setThemeColor(color);
                recreate();
            });
        }

        SeekBar seekFont = findViewById(R.id.seekFontSize);
        TextView textFontValue = findViewById(R.id.textFontSizeValue);
        int[] sizes = {12, 14, 16, 18, 20};
        if (seekFont != null && textFontValue != null) {
            int fs = config.getFontSize();
            int idx = 1;
            final int[] oldIdx = {idx};
            for (int i = 0; i < sizes.length; i++) if (sizes[i] == fs) { idx = i; break; }
            oldIdx[0] = idx;
            seekFont.setProgress(idx);
            textFontValue.setText(String.valueOf(sizes[idx]));
            seekFont.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    if (fromUser) {
                        int v = sizes[Math.min(progress, sizes.length - 1)];
                        textFontValue.setText(String.valueOf(v));
                        config.setFontSize(v);
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar s) {}
                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    int current = Math.min(s.getProgress(), sizes.length - 1);
                    if (current != oldIdx[0]) {
                        oldIdx[0] = current;
                        recreate();
                    }
                }
            });
        }

        findViewById(R.id.btnNewChat).setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("action", "new_chat"));
            finish();
        });
        findViewById(R.id.btnExport).setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("action", "export"));
            finish();
        });
    }

    private void applyTheme(String theme) {
        int mode = "dark".equals(theme) ? AppCompatDelegate.MODE_NIGHT_YES
                : "light".equals(theme) ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    private void setupModelManagement(View root) {
        providerManager = new ProviderManager(this);

        View btnAdd = root.findViewById(R.id.btnAddProvider);
        if (btnAdd != null) {
            btnAdd.setOnClickListener(v -> showAddProviderDialog());
        }

        RecyclerView recyclerProviders = root.findViewById(R.id.recyclerProviders);
        if (recyclerProviders != null) {
            recyclerProviders.setLayoutManager(new LinearLayoutManager(this));
            recyclerProviders.setNestedScrollingEnabled(false);
            providerAdapter = new ProviderListAdapter();
            providerAdapter.setOnProviderClickListener(p -> {
                Intent i = new Intent(this, ProviderDetailActivity.class);
                i.putExtra(ProviderDetailActivity.EXTRA_PROVIDER_ID, p.id);
                startActivity(i);
            });
            recyclerProviders.setAdapter(providerAdapter);
        }

        refreshProviders();
    }

    private void showAddProviderDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_provider_select, null);
        RecyclerView recycler = dialogView.findViewById(R.id.recyclerCatalog);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        ProviderCatalogAdapter catalogAdapter = new ProviderCatalogAdapter();
        Set<String> enabled = new HashSet<>();
        for (ProviderInfo p : providerManager.getAllProviders()) {
            enabled.add(p.id);
        }
        catalogAdapter.setData(ProviderCatalog.getAll(), enabled);
        AlertDialog d = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        catalogAdapter.setOnItemClickListener(item -> {
            if (!providerManager.isEnabled(item.id)) {
                providerManager.addPresetProvider(item.id);
                refreshProviders();
            }
            d.dismiss();
            startActivity(new Intent(this, ProviderDetailActivity.class).putExtra(ProviderDetailActivity.EXTRA_PROVIDER_ID, item.id));
        });
        recycler.setAdapter(catalogAdapter);
        d.show();
    }

    private void refreshProviders() {
        if (providerAdapter != null) {
            List<ProviderInfo> list = providerManager != null ? providerManager.getAllProviders() : java.util.Collections.emptyList();
            providerAdapter.setProviders(list);
        }
    }

    private void showCharacterMemorySettingsDialog() {
        CharacterMemoryConfigStore store = new CharacterMemoryConfigStore(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_character_memory_settings, null);
        MaterialSwitch switchEnabled = view.findViewById(R.id.switchCharacterMemoryEnabled);
        TextInputEditText editBaseUrl = view.findViewById(R.id.editCharacterMemoryBaseUrl);
        TextInputEditText editApiKey = view.findViewById(R.id.editCharacterMemoryApiKey);
        TextInputEditText editConnectTimeout = view.findViewById(R.id.editCharacterMemoryConnectTimeout);
        TextInputEditText editReadTimeout = view.findViewById(R.id.editCharacterMemoryReadTimeout);
        MaterialSwitch switchDebug = view.findViewById(R.id.switchCharacterMemoryDebug);
        if (switchEnabled != null) switchEnabled.setChecked(store.isEnabled());
        if (editBaseUrl != null) editBaseUrl.setText(store.getBaseUrl());
        if (editApiKey != null) editApiKey.setText(store.getApiKey());
        if (editConnectTimeout != null) editConnectTimeout.setText(String.valueOf(store.getConnectTimeoutMs()));
        if (editReadTimeout != null) editReadTimeout.setText(String.valueOf(store.getReadTimeoutMs()));
        if (switchDebug != null) switchDebug.setChecked(store.isDebugLogEnabled());

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.character_memory_settings)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    boolean enabled = switchEnabled != null && switchEnabled.isChecked();
                    boolean debug = switchDebug != null && switchDebug.isChecked();
                    String baseUrl = editBaseUrl != null && editBaseUrl.getText() != null
                            ? editBaseUrl.getText().toString().trim() : "";
                    String apiKey = editApiKey != null && editApiKey.getText() != null
                            ? editApiKey.getText().toString().trim() : "";
                    int connectTimeoutMs = parseIntOrDefault(
                            editConnectTimeout != null && editConnectTimeout.getText() != null
                                    ? editConnectTimeout.getText().toString().trim() : "",
                            store.getConnectTimeoutMs()
                    );
                    int readTimeoutMs = parseIntOrDefault(
                            editReadTimeout != null && editReadTimeout.getText() != null
                                    ? editReadTimeout.getText().toString().trim() : "",
                            store.getReadTimeoutMs()
                    );
                    store.saveAll(enabled, baseUrl, apiKey, connectTimeoutMs, readTimeoutMs, debug);
                    Toast.makeText(this, R.string.character_memory_saved, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private int parseIntOrDefault(String text, int fallback) {
        if (text == null || text.trim().isEmpty()) return fallback;
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshProviders();
    }
}
