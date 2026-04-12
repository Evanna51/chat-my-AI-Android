package com.example.aichat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

class SessionListAdapter : RecyclerView.Adapter<SessionListAdapter.Holder>() {

    private var items: List<SessionSummary> = ArrayList()
    private var listener: OnSessionClickListener? = null
    private var actionListener: SessionActionListener? = null

    fun interface OnSessionClickListener {
        fun onSessionClick(s: SessionSummary)
    }

    interface SessionActionListener {
        fun onHide(s: SessionSummary)
        fun onDelete(s: SessionSummary)
    }

    fun setOnSessionClickListener(l: OnSessionClickListener) { listener = l }
    fun setSessionActionListener(l: SessionActionListener) { actionListener = l }

    fun setSessions(list: List<SessionSummary>?) {
        items = list ?: ArrayList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        if (position < 0 || position >= items.size) return
        val s = items[position] ?: return
        h.avatar?.let { av ->
            val avatar = if (s.avatar != null && s.avatar.trim().isNotEmpty()) s.avatar.trim() else "🤖"
            av.setText(avatar)
        }
        h.title?.let { tv ->
            var title = if (s.title != null && s.title.isNotEmpty()) s.title else "新对话"
            if (s.pinned) title = "📌 $title"
            else if (s.favorite) title = "★ $title"
            tv.setText(title)
        }
        h.time?.setText(if (s.lastAt > 0) SDF.format(Date(s.lastAt)) else "")
        h.itemView.setOnClickListener { listener?.onSessionClick(s) }
        h.hide?.setOnClickListener { actionListener?.onHide(s) }
        h.delete?.setOnClickListener { actionListener?.onDelete(s) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView? = v.findViewById(R.id.sessionAvatar)
        val title: TextView? = v.findViewById(R.id.sessionTitle)
        val time: TextView? = v.findViewById(R.id.sessionTime)
        val hide: ImageButton? = v.findViewById(R.id.btnHideSession)
        val delete: ImageButton? = v.findViewById(R.id.btnDeleteSession)
    }

    companion object {
        private val SDF = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }
}
