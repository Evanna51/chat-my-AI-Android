package com.example.aichat;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

public class ProactiveMessageNotifier {
    public static final String CHANNEL_ID = "proactive_message_channel";
    private static final String CHANNEL_NAME = "人物主动消息";
    private static final String CHANNEL_DESC = "人物助手主动发来的消息提醒";

    private final Context appContext;

    public ProactiveMessageNotifier(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = appContext.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(CHANNEL_DESC);
        nm.createNotificationChannel(channel);
    }

    public boolean canNotify() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean notifyMessage(String messageId, String title, String body, String sessionId, String assistantId) {
        ensureChannel();
        if (!canNotify()) return false;
        String finalTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : "人物助手有新消息";
        String finalBody = (body != null && !body.trim().isEmpty()) ? body.trim() : "点击查看";

        Intent intent;
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            intent = new Intent(appContext, ChatSessionActivity.class);
            intent.putExtra(ChatSessionActivity.EXTRA_SESSION_ID, sessionId.trim());
            if (assistantId != null && !assistantId.trim().isEmpty()) {
                intent.putExtra(ChatSessionActivity.EXTRA_ASSISTANT_ID, assistantId.trim());
            }
        } else {
            intent = new Intent(appContext, MainActivity.class);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int requestCode = messageId != null ? Math.abs(messageId.hashCode()) : (int) System.currentTimeMillis();
        PendingIntent contentIntent = PendingIntent.getActivity(
                appContext,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(finalTitle)
                .setContentText(finalBody)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(finalBody))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(contentIntent);

        NotificationManagerCompat.from(appContext).notify(requestCode, builder.build());
        return true;
    }
}
