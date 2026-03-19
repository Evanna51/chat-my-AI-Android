package com.example.aichat;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public abstract class ThemedActivity extends AppCompatActivity {
    private int appliedThemeResId;

    @Override
    protected void attachBaseContext(Context newBase) {
        Context wrapped = ThemeSettingsHelper.wrapForFontScale(newBase);
        super.attachBaseContext(wrapped != null ? wrapped : newBase);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        appliedThemeResId = ThemeSettingsHelper.resolveThemeResId(this);
        setTheme(appliedThemeResId);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        int currentThemeResId = ThemeSettingsHelper.resolveThemeResId(this);
        if (currentThemeResId != appliedThemeResId) {
            recreate();
        }
    }
}
