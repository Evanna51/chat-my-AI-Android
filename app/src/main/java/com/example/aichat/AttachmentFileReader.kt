package com.example.aichat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object AttachmentFileReader {

    private const val MAX_TEXT_BYTES = 200 * 1024
    private const val MAX_PDF_CHARS = 200_000

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "json", "yaml", "yml", "xml",
        "csv", "tsv", "log", "html", "htm", "css", "ini", "toml",
        "kt", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs",
        "c", "cc", "cpp", "h", "hpp", "swift", "sh", "bash", "zsh",
        "rb", "php", "lua", "scala", "sql", "gradle", "properties",
        "env", "conf", "cfg"
    )

    sealed class Result {
        data class Text(val displayName: String, val content: String, val truncated: Boolean) : Result()
        data class Unsupported(val displayName: String, val reason: String) : Result()
        data class Failure(val displayName: String, val reason: String) : Result()
    }

    fun read(context: Context, uri: Uri): Result {
        val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: uri.toString()
        val mime = context.contentResolver.getType(uri) ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()

        return when {
            mime == "application/pdf" || ext == "pdf" -> readPdf(context, uri, name)
            mime.startsWith("text/") || ext in TEXT_EXTENSIONS || isLikelyJson(mime, ext) ->
                readText(context, uri, name)
            else -> Result.Unsupported(name, formatUnsupportedReason(mime, ext))
        }
    }

    private fun isLikelyJson(mime: String, ext: String): Boolean {
        return mime.contains("json") || mime.contains("xml") || ext == "json"
    }

    private fun readText(context: Context, uri: Uri, displayName: String): Result {
        return try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return Result.Failure(displayName, "无法打开文件流")
                val buf = ByteArray(MAX_TEXT_BYTES + 1)
                var total = 0
                while (total < buf.size) {
                    val read = input.read(buf, total, buf.size - total)
                    if (read <= 0) break
                    total += read
                }
                val truncated = total > MAX_TEXT_BYTES
                val effective = if (truncated) MAX_TEXT_BYTES else total
                val text = String(buf, 0, effective, StandardCharsets.UTF_8)
                Result.Text(displayName, text, truncated)
            }
        } catch (e: Exception) {
            Result.Failure(displayName, e.message ?: "读取失败")
        }
    }

    private fun readPdf(context: Context, uri: Uri, displayName: String): Result {
        return try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return Result.Failure(displayName, "无法打开文件流")
                PDDocument.load(input).use { doc ->
                    val stripper = PDFTextStripper()
                    val raw = stripper.getText(doc) ?: ""
                    val truncated = raw.length > MAX_PDF_CHARS
                    val text = if (truncated) raw.substring(0, MAX_PDF_CHARS) else raw
                    Result.Text(displayName, text, truncated)
                }
            }
        } catch (e: Exception) {
            Result.Failure(displayName, e.message ?: "PDF 解析失败")
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    private fun formatUnsupportedReason(mime: String, ext: String): String {
        val tag = when {
            mime.startsWith("image/") -> "图片"
            mime.startsWith("audio/") -> "音频"
            mime.startsWith("video/") -> "视频"
            ext in setOf("docx", "doc") -> "Word 文档"
            ext in setOf("xlsx", "xls") -> "Excel 表格"
            ext in setOf("pptx", "ppt") -> "PPT 演示文稿"
            ext in setOf("zip", "rar", "7z", "tar", "gz") -> "压缩包"
            ext == "epub" -> "EPUB"
            else -> if (ext.isNotEmpty()) ".$ext" else mime.ifEmpty { "未知" }
        }
        return tag
    }
}
