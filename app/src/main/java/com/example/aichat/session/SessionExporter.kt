package com.example.aichat.session

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.StringRes
import com.example.aichat.Message
import com.example.aichat.R
import com.example.aichat.SessionOutlineItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话导出 —— 入口在 ChatSessionActivity.showSessionMoreMenu。
 *
 * 流程：
 * 1. [show] 弹格式选择对话框（JSON / TXT / HTML，默认 JSON）
 * 2. 若 [SessionModeStrategy.supportsOutlineExport]（writer 模式），再弹范围对话框
 *    （全部 / 仅助手 / 仅大纲，默认全部）；非 writer 模式默认 ALL，直接进第 3 步
 * 3. SAF `ACTION_CREATE_DOCUMENT` 让用户选保存位置，文件名 = `<sessionTitle>_<yyyyMMdd_HHmmss>.<ext>`
 * 4. Activity 收到 result URI 后回调 [onDocumentCreated]，根据已暂存的 [pendingFormat]/[pendingScope]
 *    生成内容并写入
 *
 * Activity 责任：注册 `ActivityResultContracts.StartActivityForResult` launcher，在 callback 里
 * 调 [onDocumentCreated]。本类持有 launcher 引用，但不参与生命周期管理。
 */
class SessionExporter(
    private val activity: Activity,
    private val sessionId: String,
    private val mode: SessionModeStrategy,
    private val createDocumentLauncher: ActivityResultLauncher<Intent>,
    private val getMessages: () -> List<Message>,
    private val getSessionTitle: () -> String,
    private val getOutline: () -> List<SessionOutlineItem>,
) {

    enum class Format(val mime: String, val ext: String, @StringRes val labelRes: Int) {
        JSON("application/json", "json", R.string.export_format_json),
        TXT("text/plain", "txt", R.string.export_format_txt),
        HTML("text/html", "html", R.string.export_format_html),
    }

    enum class Scope(@StringRes val labelRes: Int) {
        ALL(R.string.export_scope_all),
        ASSISTANT_ONLY(R.string.export_scope_assistant_only),
        OUTLINE_ONLY(R.string.export_scope_outline_only),
    }

    private var pendingFormat: Format = Format.JSON
    private var pendingScope: Scope = Scope.ALL

    /** Public entry：弹格式选择对话框。 */
    fun show() {
        pendingFormat = Format.JSON
        pendingScope = Scope.ALL
        showFormatDialog()
    }

    private fun showFormatDialog() {
        val formats = Format.values()
        val labels = formats.map { activity.getString(it.labelRes) }.toTypedArray<CharSequence>()
        var checked = formats.indexOf(pendingFormat).coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.export_format_title)
            .setSingleChoiceItems(labels, checked) { _, which -> checked = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pendingFormat = formats[checked]
                if (mode.supportsOutlineExport) {
                    showScopeDialog()
                } else {
                    pendingScope = Scope.ALL
                    launchSaveDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showScopeDialog() {
        val scopes = Scope.values()
        val labels = scopes.map { activity.getString(it.labelRes) }.toTypedArray<CharSequence>()
        var checked = scopes.indexOf(pendingScope).coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.export_scope_title)
            .setSingleChoiceItems(labels, checked) { _, which -> checked = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pendingScope = scopes[checked]
                launchSaveDialog()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchSaveDialog() {
        val title = sanitizeFilename(getSessionTitle())
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "${title}_${stamp}.${pendingFormat.ext}"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(pendingFormat.mime)
            .putExtra(Intent.EXTRA_TITLE, filename)
        try {
            createDocumentLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(
                activity,
                activity.getString(R.string.export_failed, e.message.orEmpty()),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** Activity callback：SAF 返回 URI 后调用，把内容写入 URI 指向的 document. */
    fun onDocumentCreated(uri: Uri?) {
        if (uri == null) return
        try {
            val content = buildContent(pendingFormat, pendingScope)
            activity.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(activity, R.string.export_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                activity,
                activity.getString(R.string.export_failed, e.message.orEmpty()),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // ─────────── content builders ───────────

    private fun buildContent(format: Format, scope: Scope): String = when (format) {
        Format.JSON -> buildJson(scope)
        Format.TXT -> buildTxt(scope)
        Format.HTML -> buildHtml(scope)
    }

    private fun buildJson(scope: Scope): String {
        val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        val payload: MutableMap<String, Any?> = LinkedHashMap()
        payload["sessionId"] = sessionId
        payload["sessionTitle"] = getSessionTitle()
        payload["exportedAt"] = isoNow()
        payload["scope"] = scope.name
        if (scope == Scope.OUTLINE_ONLY) {
            payload["outline"] = getOutline().map(::outlineToMap)
        } else {
            payload["messages"] = filterMessages(scope).map(::messageToMap)
            if (mode.supportsOutlineExport && scope == Scope.ALL) {
                val outline = getOutline()
                if (outline.isNotEmpty()) payload["outline"] = outline.map(::outlineToMap)
            }
        }
        return gson.toJson(payload)
    }

    private fun buildTxt(scope: Scope): String {
        val sb = StringBuilder()
        sb.append("# ").append(getSessionTitle()).append('\n')
        sb.append("Session: ").append(sessionId).append('\n')
        sb.append("Exported: ").append(isoNow()).append('\n')
        sb.append("Scope: ").append(scope.name).append('\n')
        sb.append('\n')

        if (scope == Scope.OUTLINE_ONLY) {
            appendOutlineTxt(sb, getOutline())
            return sb.toString()
        }

        for (m in filterMessages(scope)) {
            sb.append("── ").append(roleLabel(m.role)).append(" · ")
                .append(formatTimestamp(m.createdAt)).append(" ──\n")
            val content = m.content?.trim().orEmpty()
            if (content.isNotEmpty()) sb.append(content).append('\n')
            val reasoning = m.reasoning?.trim().orEmpty()
            if (reasoning.isNotEmpty()) {
                sb.append("\n[reasoning]\n").append(reasoning).append('\n')
            }
            sb.append('\n')
        }

        if (mode.supportsOutlineExport && scope == Scope.ALL) {
            val outline = getOutline()
            if (outline.isNotEmpty()) {
                sb.append("\n══ Outline ══\n\n")
                appendOutlineTxt(sb, outline)
            }
        }
        return sb.toString()
    }

    private fun appendOutlineTxt(sb: StringBuilder, outline: List<SessionOutlineItem>) {
        if (outline.isEmpty()) {
            sb.append(activity.getString(R.string.export_empty_outline)).append('\n')
            return
        }
        for (item in outline) {
            sb.append('[').append(item.type).append("] ").append(item.title).append('\n')
            val c = item.content.trim()
            if (c.isNotEmpty()) sb.append(c).append('\n')
            sb.append('\n')
        }
    }

    private fun buildHtml(scope: Scope): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">\n")
        sb.append("<title>").append(escapeHtml(getSessionTitle())).append("</title>\n")
        sb.append("<style>")
        sb.append("body{font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;max-width:760px;margin:24px auto;padding:0 16px;color:#222;line-height:1.6}")
        sb.append("h1{font-size:1.4em;margin:0 0 4px}")
        sb.append(".meta{color:#888;font-size:0.85em;margin-bottom:24px}")
        sb.append(".msg{padding:12px 14px;border-radius:10px;margin:10px 0;white-space:pre-wrap;word-break:break-word}")
        sb.append(".user{background:#DCF8C6;border:1px solid #cdebb6}")
        sb.append(".assistant{background:#fff;border:1px solid #e0e0e0}")
        sb.append(".system{background:#f5f5f5;border:1px dashed #ccc;font-style:italic}")
        sb.append(".role{font-weight:600;font-size:0.85em;color:#666;margin-bottom:4px}")
        sb.append(".time{color:#aaa;font-size:0.75em;margin-left:6px}")
        sb.append(".reasoning{margin-top:8px;padding:8px;background:#fafafa;border-left:3px solid #ddd;color:#555;font-size:0.9em}")
        sb.append(".outline{background:#fff8e7;border:1px solid #f0e0a8;border-radius:10px;padding:12px 14px;margin:10px 0}")
        sb.append(".outline-type{display:inline-block;background:#f0b;color:#fff;padding:1px 6px;border-radius:3px;font-size:0.75em;margin-right:6px}")
        sb.append("h2{font-size:1.1em;margin-top:32px;padding-top:8px;border-top:1px solid #eee}")
        sb.append("</style></head><body>\n")

        sb.append("<h1>").append(escapeHtml(getSessionTitle())).append("</h1>\n")
        sb.append("<div class=\"meta\">")
        sb.append("Session: ").append(escapeHtml(sessionId)).append(" · ")
        sb.append("Exported: ").append(escapeHtml(isoNow())).append(" · ")
        sb.append("Scope: ").append(scope.name)
        sb.append("</div>\n")

        if (scope == Scope.OUTLINE_ONLY) {
            appendOutlineHtml(sb, getOutline())
            sb.append("</body></html>")
            return sb.toString()
        }

        for (m in filterMessages(scope)) {
            val cls = when (m.role) {
                Message.ROLE_USER -> "user"
                Message.ROLE_ASSISTANT -> "assistant"
                else -> "system"
            }
            sb.append("<div class=\"msg ").append(cls).append("\">")
            sb.append("<div class=\"role\">").append(roleLabel(m.role))
            sb.append("<span class=\"time\">").append(escapeHtml(formatTimestamp(m.createdAt))).append("</span>")
            sb.append("</div>")
            val content = m.content?.trim().orEmpty()
            if (content.isNotEmpty()) sb.append(escapeHtml(content))
            val reasoning = m.reasoning?.trim().orEmpty()
            if (reasoning.isNotEmpty()) {
                sb.append("<div class=\"reasoning\">").append(escapeHtml(reasoning)).append("</div>")
            }
            sb.append("</div>\n")
        }

        if (mode.supportsOutlineExport && scope == Scope.ALL) {
            val outline = getOutline()
            if (outline.isNotEmpty()) {
                sb.append("<h2>Outline</h2>\n")
                appendOutlineHtml(sb, outline)
            }
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun appendOutlineHtml(sb: StringBuilder, outline: List<SessionOutlineItem>) {
        if (outline.isEmpty()) {
            sb.append("<p>").append(escapeHtml(activity.getString(R.string.export_empty_outline))).append("</p>\n")
            return
        }
        for (item in outline) {
            sb.append("<div class=\"outline\">")
            sb.append("<span class=\"outline-type\">").append(escapeHtml(item.type)).append("</span>")
            sb.append("<strong>").append(escapeHtml(item.title)).append("</strong>")
            val c = item.content.trim()
            if (c.isNotEmpty()) {
                sb.append("<div style=\"margin-top:6px;white-space:pre-wrap;word-break:break-word\">")
                    .append(escapeHtml(c))
                    .append("</div>")
            }
            sb.append("</div>\n")
        }
    }

    // ─────────── helpers ───────────

    private fun filterMessages(scope: Scope): List<Message> {
        val all = getMessages()
        return when (scope) {
            Scope.ALL -> all
            Scope.ASSISTANT_ONLY -> all.filter { it.role == Message.ROLE_ASSISTANT }
            Scope.OUTLINE_ONLY -> emptyList()
        }
    }

    private fun messageToMap(m: Message): Map<String, Any?> {
        val out: MutableMap<String, Any?> = LinkedHashMap()
        out["role"] = roleLabel(m.role)
        out["createdAt"] = m.createdAt
        out["createdAtIso"] = formatTimestamp(m.createdAt)
        val content = m.content?.trim().orEmpty()
        if (content.isNotEmpty()) out["content"] = content
        val reasoning = m.reasoning?.trim().orEmpty()
        if (reasoning.isNotEmpty()) out["reasoning"] = reasoning
        return out
    }

    private fun outlineToMap(item: SessionOutlineItem): Map<String, Any?> {
        val out: MutableMap<String, Any?> = LinkedHashMap()
        out["type"] = item.type
        out["title"] = item.title
        out["content"] = item.content
        out["createdAt"] = item.createdAt
        return out
    }

    private fun roleLabel(role: Int): String = when (role) {
        Message.ROLE_USER -> "user"
        Message.ROLE_ASSISTANT -> "assistant"
        Message.ROLE_SYSTEM -> "system"
        else -> "unknown"
    }

    private fun formatTimestamp(ms: Long): String {
        if (ms <= 0L) return ""
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    /** 文件名安全化：去掉 SAF 不友好字符；空标题用 "session"。 */
    private fun sanitizeFilename(raw: String): String {
        val trimmed = raw.trim().ifEmpty { "session" }
        return trimmed.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40)
    }

    private fun escapeHtml(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
