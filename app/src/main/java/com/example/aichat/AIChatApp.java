package com.example.aichat;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.mikepenz.iconics.Iconics;

public class AIChatApp extends Application implements DefaultLifecycleObserver {
    @Override
    public void onCreate() {
        super.onCreate();
        Iconics.init(this); // typefaces auto-register via ContentProvider
        applyTheme();
        RoomMigrationHelper.migrateIfNeeded(this);
        new ProactiveMessageNotifier(this).ensureChannel();
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
        ProactiveMessageWorkScheduler.scheduleNext(this);
    }

    private void applyTheme() {
        String theme = new ConfigManager(this).getTheme();
        int mode = "dark".equals(theme) ? AppCompatDelegate.MODE_NIGHT_YES
                : "light".equals(theme) ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    @Override
    public void onStart(LifecycleOwner owner) {
        ProactiveMessageWorkScheduler.cancel(this);
    }

    @Override
    public void onStop(LifecycleOwner owner) {
        ProactiveMessageWorkScheduler.scheduleNext(this);
    }
}
