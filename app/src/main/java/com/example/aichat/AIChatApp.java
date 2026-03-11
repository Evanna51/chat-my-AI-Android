package com.example.aichat;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

public class AIChatApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        applyTheme();
    }

    private void applyTheme() {
        String theme = new ConfigManager(this).getTheme();
        int mode = "dark".equals(theme) ? AppCompatDelegate.MODE_NIGHT_YES
                : "light".equals(theme) ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        AppCompatDelegate.setDefaultNightMode(mode);
    }
}
