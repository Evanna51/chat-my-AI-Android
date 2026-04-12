package com.example.aichat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class ProactiveMessageNotifier(context: Context) {

    companion object {
        const val CHANNEL_ID = "proactive_message_channel"
        private const val CHANNEL_NAME = "人物主动消息"
        private const val CHANNEL_DESC = "人物助手主动发来的消息提醒"
    }

    private val appContext: Context = context.applicationContext

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = CHANNEL_DESC
        nm.createNotificationChannel(channel)
    }

    fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun notifyMessage(
        messageId: String?,
        title: String?,
        body: String?,
        sessionId: String?,
        assistantId: String?
    ): Boolean {
        ensureChannel()
        if (!canNotify()) return false
        val finalTitle = if (!title.isNullOrBlank()) title.trim() else "人物助手有新消息"
        val finalBody = if (!body.isNullOrBlank()) body.trim() else "点击查看"

        val intent: Intent
        if (!sessionId.isNullOrBlank()) {
            intent = Intent(appContext, ChatSessionActivity::class.java)
            intent.putExtra(ChatSessionActivity.EXTRA_SESSION_ID, sessionId.trim())
            if (!assistantId.isNullOrBlank()) {
                intent.putExtra(ChatSessionActivity.EXTRA_ASSISTANT_ID, assistantId.trim())
            }
        } else {
            intent = Intent(appContext, MainActivity::class.java)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val requestCode = if (messageId != null) Math.abs(messageId.hashCode()) else System.currentTimeMillis().toInt()
        val contentIntent = PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(finalTitle)
            .setContentText(finalBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(finalBody))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)

        NotificationManagerCompat.from(appContext).notify(requestCode, builder.build())
        return true
    }
}
