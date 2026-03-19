package com.example.aichat;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.widget.ImageView;
import android.widget.TextView;

public final class AssistantAvatarHelper {
    private AssistantAvatarHelper() {}

    public static void bindAvatar(ImageView imageView, TextView textView, MyAssistant assistant, String fallbackName) {
        String imageBase64 = assistant != null ? safe(assistant.avatarImageBase64) : "";
        Bitmap avatarBitmap = decodeBase64(imageBase64);
        if (avatarBitmap != null && imageView != null) {
            imageView.setImageBitmap(avatarBitmap);
            imageView.setVisibility(ImageView.VISIBLE);
            if (textView != null) textView.setVisibility(TextView.GONE);
            return;
        }
        if (imageView != null) imageView.setVisibility(ImageView.GONE);
        if (textView != null) {
            textView.setText(resolveTextAvatar(assistant, fallbackName));
            textView.setVisibility(TextView.VISIBLE);
        }
    }

    public static String resolveTextAvatar(MyAssistant assistant, String fallbackName) {
        String avatarText = assistant != null ? safe(assistant.avatar) : "";
        if (!avatarText.isEmpty()) return avatarText;
        String fallback = safe(fallbackName);
        if (!fallback.isEmpty()) return fallback.substring(0, 1);
        return "助";
    }

    public static Bitmap decodeBase64(String base64) {
        if (base64 == null || base64.trim().isEmpty()) return null;
        try {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
