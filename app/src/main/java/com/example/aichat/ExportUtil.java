package com.example.aichat;

import android.content.ContentValues;
import android.content.Context;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ExportUtil {

    public static void exportToFile(Context context, String sessionId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            Toast.makeText(context, "没有可导出的记录", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("AI Chat 导出 - ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()))
                .append("\n\n");

        for (Message m : messages) {
            String role = m.role == Message.ROLE_USER ? "用户" : "助手";
            sb.append("[").append(role).append("]\n");
            sb.append(m.content).append("\n\n");
        }

        String filename = "ai_chat_" + sessionId + "_" + System.currentTimeMillis() + ".txt";
        String content = sb.toString();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");

            try {
                OutputStream os = context.getContentResolver().openOutputStream(
                        context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values));
                if (os != null) {
                    os.write(content.getBytes("UTF-8"));
                    os.close();
                    Toast.makeText(context, R.string.export_success, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show();
                }
            } catch (IOException e) {
                Toast.makeText(context, context.getString(R.string.export_failed) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            java.io.File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            java.io.File file = new java.io.File(dir, filename);
            try {
                java.io.FileWriter fw = new java.io.FileWriter(file);
                fw.write(content);
                fw.close();
                Toast.makeText(context, R.string.export_success, Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(context, context.getString(R.string.export_failed) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }
}
