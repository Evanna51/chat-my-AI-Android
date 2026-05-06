package com.example.aichat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

class SessionListAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val groupKey: String, val title: String, val count: Int, val collapsed: Boolean) : Row()
        data class Session(val session: SessionSummary) : Row()
    }

    private var rows: List<Row> = ArrayList()
    private var listener: OnSessionClickListener? = null
    private var longPressListener: SessionLongPressListener? = null
    private var headerListener: OnHeaderClickListener? = null

    fun interface OnSessionClickListener {
        fun onSessionClick(s: SessionSummary)
    }

    fun interface SessionLongPressListener {
        fun onLongPress(session: SessionSummary, anchorView: View)
    }

    fun interface OnHeaderClickListener {
        fun onHeaderClick(groupKey: String)
    }

    fun setOnSessionClickListener(l: OnSessionClickListener) { listener = l }
    fun setSessionLongPressListener(l: SessionLongPressListener) { longPressListener = l }
    fun setOnHeaderClickListener(l: OnHeaderClickListener) { headerListener = l }

    fun setRows(list: List<Row>?) {
        rows = list ?: ArrayList()
        notifyDataSetChanged()
    }

    fun setSessions(list: List<SessionSummary>?) {
        val r = ArrayList<Row>()
        if (list != null) for (s in list) r.add(Row.Session(s))
        rows = r
        notifyDataSetChanged()
    }

    fun getSessionAt(position: Int): SessionSummary {
        val row = rows[position]
        if (row is Row.Session) return row.session
        throw IllegalStateException("Row at $position is a header, not a session")
    }

    fun isSessionRow(position: Int): Boolean = position in rows.indices && rows[position] is Row.Session

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> VIEW_TYPE_HEADER
        is Row.Session -> VIEW_TYPE_SESSION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_session_group_header, parent, false))
        } else {
            SessionHolder(inflater.inflate(R.layout.item_session, parent, false))
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> bindHeader(h as HeaderHolder, row)
            is Row.Session -> bindSession(h as SessionHolder, row.session)
        }
    }

    private fun bindHeader(h: HeaderHolder, row: Row.Header) {
        h.title?.text = row.title
        h.count?.text = "(${row.count})"
        h.chevron?.rotation = if (row.collapsed) 0f else 90f
        h.itemView.setOnClickListener { headerListener?.onHeaderClick(row.groupKey) }
        h.itemView.setOnLongClickListener(null)
    }

    private fun bindSession(h: SessionHolder, s: SessionSummary) {
        // 头像走 AssistantAvatarHelper 同款逻辑：图片优先 → 文字 emoji → 默认。
        // 这里用一个临时 MyAssistant 把 SessionSummary 的两路头像数据转给 helper。
        val avatarShim = MyAssistant().apply {
            avatar = s.avatar
            avatarImageBase64 = s.avatarImageBase64
        }
        AssistantAvatarHelper.bindAvatar(h.avatarImage, h.avatar, avatarShim, "🤖")
        h.title?.let { tv ->
            var title = if (s.title != null && s.title.isNotEmpty()) s.title else "新对话"
            if (s.favorite && !s.pinned) title = "★ $title"
            tv.text = title
        }
        h.preview?.text = preview(s.lastMessage)
        h.time?.text = formatRelative(s.lastAt)
        h.pin?.visibility = if (s.pinned) View.VISIBLE else View.GONE
        h.itemView.setOnClickListener { listener?.onSessionClick(s) }
        h.itemView.setOnLongClickListener {
            longPressListener?.onLongPress(s, it)
            true
        }
    }

    private fun preview(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val cleaned = raw.replace("\n", " ").trim()
        return if (cleaned.length > 40) cleaned.substring(0, 40) + "…" else cleaned
    }

    private fun formatRelative(ts: Long): String {
        if (ts <= 0L) return ""
        val today = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val day = 86_400_000L
        return when {
            ts >= today -> SDF_TIME.format(Date(ts))
            ts >= today - day -> "昨天"
            ts >= today - 7 * day -> "${(today - ts) / day + 1}天前"
            else -> SDF_DATE.format(Date(ts))
        }
    }

    override fun getItemCount(): Int = rows.size

    class SessionHolder(v: View) : RecyclerView.ViewHolder(v) {
        val avatarImage: ImageView? = v.findViewById(R.id.sessionAvatarImage)
        val avatar: TextView? = v.findViewById(R.id.sessionAvatar)
        val title: TextView? = v.findViewById(R.id.sessionTitle)
        val time: TextView? = v.findViewById(R.id.sessionTime)
        val preview: TextView? = v.findViewById(R.id.sessionPreview)
        val pin: ImageView? = v.findViewById(R.id.sessionPin)
    }

    class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView? = v.findViewById(R.id.groupTitle)
        val count: TextView? = v.findViewById(R.id.groupCount)
        val chevron: ImageView? = v.findViewById(R.id.groupChevron)
    }

    companion object {
        private const val VIEW_TYPE_SESSION = 0
        private const val VIEW_TYPE_HEADER = 1
        private val SDF_TIME = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val SDF_DATE = SimpleDateFormat("MM-dd", Locale.getDefault())
        const val GROUP_WRITER = "writer"
    }
}
