package com.example.aichat

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExportUtil {

    companion object {
        @JvmStatic
        fun exportToFile(context: Context, sessionId: String, messages: List<Message>?) {
            if (messages.isNullOrEmpty()) {
                Toast.makeText(context, "没有可导出的记录", Toast.LENGTH_SHORT).show()
                return
            }

            val sb = StringBuilder()
            sb.append("AI Chat 导出 - ")
                .append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
                .append("\n\n")

            for (m in messages) {
                val role = if (m.role == Message.ROLE_USER) "用户" else "助手"
                sb.append("[").append(role).append("]\n")
                sb.append(m.content).append("\n\n")
            }

            val filename = "ai_chat_${sessionId}_${System.currentTimeMillis()}.txt"
            val content = sb.toString()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                }
                try {
                    val os = context.contentResolver.openOutputStream(
                        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                    )
                    if (os != null) {
                        os.write(content.toByteArray(Charsets.UTF_8))
                        os.close()
                        Toast.makeText(context, R.string.export_success, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: IOException) {
                    Toast.makeText(context, context.getString(R.string.export_failed) + ": " + e.message, Toast.LENGTH_SHORT).show()
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = java.io.File(dir, filename)
                try {
                    val fw = FileWriter(file)
                    fw.write(content)
                    fw.close()
                    Toast.makeText(context, R.string.export_success, Toast.LENGTH_SHORT).show()
                } catch (e: IOException) {
                    Toast.makeText(context, context.getString(R.string.export_failed) + ": " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
