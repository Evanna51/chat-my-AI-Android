package com.example.aichat.session

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.aichat.AttachmentFileReader
import com.example.aichat.R
import java.util.concurrent.ExecutorService

/**
 * 输入框上方的「附件芯片栏」+ 待发送附件列表的统一管理。
 *
 * 从 ChatSessionActivity 抽出（R8）。把附件相关 9 个 private fun + 1 个
 * MutableList 字段 + 1 个 PendingAttachment 数据类收口到这里。
 *
 * 不负责：
 * - ActivityResultLauncher 注册（必须在 Activity 字段处 registerForActivityResult）
 * - 权限请求（locationPermissionLauncher 在 Activity）
 * - "把文本追加到 inputEdit"（photoPicker / location 选完后插入 URI 文本是 Activity 行为）
 *
 * 负责：
 * - 文件被选中后的解析 + 加入待发送列表（[onFilePicked]）
 * - chip 栏的渲染（[refresh]）
 * - 发送消息前的拼接（[composeMessageWith]）
 * - 发送完成后的清空（[clear]）
 */
class ChatAttachmentController(
    private val context: Context,
    private val scrollView: HorizontalScrollView,
    private val container: LinearLayout,
    private val executor: ExecutorService,
    private val mainHandler: Handler,
    private val host: Host,
) {

    /** Activity 暴露给 controller 的最小面 */
    interface Host {
        /** Activity 是否正在销毁 —— 异步回调时检查避免触碰已 detach 的 View */
        fun isFinishingOrDestroyed(): Boolean

        /** 附件列表有变化（增/删/清空）后调用，Activity 据此 updateSendButtonState */
        fun onAttachmentsChanged()
    }

    private data class PendingAttachment(
        val displayName: String,
        val content: String,
        val truncated: Boolean,
    )

    private val pending: MutableList<PendingAttachment> = ArrayList()

    /** 当前是否有待发送附件 */
    val hasAttachments: Boolean
        get() = pending.isNotEmpty()

    /**
     * 用户从文件选择器选中文件后调用。后台读取 → 主线程加入 pending list。
     * Activity 的 filePickerLauncher 回调直接转发到这个方法。
     */
    fun onFilePicked(uri: Uri) {
        Toast.makeText(context, R.string.attachment_reading_file, Toast.LENGTH_SHORT).show()
        executor.execute {
            val result = AttachmentFileReader.read(context, uri)
            mainHandler.post {
                if (host.isFinishingOrDestroyed()) return@post
                when (result) {
                    is AttachmentFileReader.Result.Text -> {
                        addPending(PendingAttachment(result.displayName, result.content, result.truncated))
                    }
                    is AttachmentFileReader.Result.Unsupported -> {
                        addPending(PendingAttachment(result.displayName, "", false))
                        Toast.makeText(
                            context,
                            context.getString(R.string.attachment_unsupported_format, result.reason),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is AttachmentFileReader.Result.Failure -> {
                        Toast.makeText(
                            context,
                            context.getString(R.string.attachment_read_failed, result.reason),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    /** 主动添加一个附件（私有；由 onFilePicked 调用）*/
    private fun addPending(att: PendingAttachment) {
        pending.add(att)
        refresh()
        host.onAttachmentsChanged()
    }

    private fun removePending(att: PendingAttachment) {
        if (pending.remove(att)) {
            refresh()
            host.onAttachmentsChanged()
        }
    }

    /** 发送成功后清空。空列表则 no-op + 不通知。*/
    fun clear() {
        if (pending.isEmpty()) return
        pending.clear()
        refresh()
        host.onAttachmentsChanged()
    }

    /**
     * 重绘 chip 栏。无附件 → 隐藏整个 HorizontalScrollView；
     * 有附件 → 显示 + 每条 inflate 一个 item_attachment_chip。
     * Activity 在 onCreate 找到 view 后调一次确保初始隐藏。
     */
    fun refresh() {
        container.removeAllViews()
        if (pending.isEmpty()) {
            scrollView.visibility = View.GONE
            return
        }
        scrollView.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(context)
        for (att in pending) {
            val chip = inflater.inflate(R.layout.item_attachment_chip, container, false)
            val nameView = chip.findViewById<TextView>(R.id.textAttachmentName)
            val removeBtn = chip.findViewById<ImageButton>(R.id.btnAttachmentRemove)
            val suffix = if (att.truncated) context.getString(R.string.attachment_truncated_suffix) else ""
            nameView.text = att.displayName + suffix
            removeBtn.setOnClickListener { removePending(att) }
            container.addView(chip)
        }
    }

    /**
     * 发送消息前把待发送附件拼到正文末尾。
     * 没有附件 → 原样返回 text；有附件 → 每个 `[文件: name] / ```内容``` ` 块串起来。
     */
    fun composeMessageWith(text: String): String {
        if (pending.isEmpty()) return text
        val sb = StringBuilder()
        if (text.isNotEmpty()) sb.append(text)
        for (att in pending) {
            if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
            val suffix = if (att.truncated) context.getString(R.string.attachment_truncated_suffix) else ""
            sb.append("\n[文件: ").append(att.displayName).append(suffix).append("]\n")
            if (att.content.isNotEmpty()) {
                sb.append("```\n").append(att.content).append("\n```\n")
            }
        }
        return sb.toString().trim()
    }
}
