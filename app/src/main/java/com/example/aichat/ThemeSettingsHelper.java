package com.example.aichat;

import android.content.Context;
import android.content.res.Configuration;

public final class ThemeSettingsHelper {
    private ThemeSettingsHelper() {}

    public static int resolveThemeResId(Context context) {
        String color = new ConfigManager(context).getThemeColor();
        if ("green".equals(color)) return R.style.Theme_AIChat_Green;
        if ("purple".equals(color)) return R.style.Theme_AIChat_Purple;
        if ("orange".equals(color)) return R.style.Theme_AIChat_Orange;
        return R.style.Theme_AIChat_Blue;
    }

    public static Context wrapForFontScale(Context base) {
        if (base == null) return null;
        float scale = resolveFontScale(base);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        if (Math.abs(configuration.fontScale - scale) < 0.0001f) return base;
        configuration.fontScale = scale;
        return base.createConfigurationContext(configuration);
    }

    private static float resolveFontScale(Context context) {
        int size = new ConfigManager(context).getFontSize();
        float scale = size / 14.0f;
        if (scale < 0.85f) return 0.85f;
        if (scale > 1.45f) return 1.45f;
        return scale;
    }
}
