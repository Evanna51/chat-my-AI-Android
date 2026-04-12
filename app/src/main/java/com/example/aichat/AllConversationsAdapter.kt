package com.example.aichat

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

class AllConversationsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
        private val SDF = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }

    interface ActionListener {
        fun onOpen(session: SessionSummary)
        fun onToggleCategory(category: String)
        fun onSetCategory(session: SessionSummary)
        fun onGenerateOutline(session: SessionSummary)
        fun onTogglePin(session: SessionSummary)
        fun onToggleFavorite(session: SessionSummary)
        fun onDelete(session: SessionSummary)
    }

    class Row {
        @JvmField var header: Boolean = false
        @JvmField var category: String? = null
        @JvmField var count: Int = 0
        @JvmField var collapsed: Boolean = false
        @JvmField var session: SessionSummary? = null
    }

    private val rows: MutableList<Row> = ArrayList()
    private var listener: ActionListener? = null

    fun setActionListener(listener: ActionListener) {
        this.listener = listener
    }

    fun setRows(list: List<Row>?) {
        rows.clear()
        if (list != null) rows.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        if (position < 0 || position >= rows.size) return TYPE_ITEM
        return if (rows[position].header) TYPE_HEADER else TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_conversation_category_header, parent, false)
            HeaderHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_conversation_manage, parent, false)
            ItemHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position < 0 || position >= rows.size) return
        val row = rows[position]
        if (holder is HeaderHolder) {
            val category = row.category ?: "默认"
            holder.title.setText("$category (${row.count})")
            holder.expand.setRotation(if (row.collapsed) 0f else 90f)
            holder.itemView.setOnClickListener { listener?.onToggleCategory(category) }
            return
        }
        val h = holder as ItemHolder
        val s = row.session ?: return
        var title = if (s.title != null && s.title.trim().isNotEmpty()) s.title.trim() else "新对话"
        if (s.hidden) title = "🙈 $title"
        if (s.pinned) title = "📌 $title"
        else if (s.favorite) title = "★ $title"
        h.title.setText(title)
        h.time.setText(if (s.lastAt > 0) SDF.format(Date(s.lastAt)) else "")
        if (h.outline != null) {
            val outline = if (s.outline != null) s.outline.trim() else ""
            if (outline.isEmpty()) {
                h.outline.setVisibility(View.GONE)
            } else {
                h.outline.setText(outline)
                h.outline.setVisibility(View.VISIBLE)
            }
        }
        val activeColor = MaterialColors.getColor(h.itemView, com.google.android.material.R.attr.colorPrimary)
        val normalColor = ContextCompat.getColor(h.itemView.context, R.color.list_icon_color)
        ImageViewCompat.setImageTintList(h.btnPin, ColorStateList.valueOf(if (s.pinned) activeColor else normalColor))
        ImageViewCompat.setImageTintList(h.btnFavorite, ColorStateList.valueOf(if (s.favorite) activeColor else normalColor))
        ImageViewCompat.setImageTintList(h.btnOutline, ColorStateList.valueOf(normalColor))
        ImageViewCompat.setImageTintList(h.btnCategory, ColorStateList.valueOf(normalColor))
        ImageViewCompat.setImageTintList(h.btnDelete, ColorStateList.valueOf(normalColor))
        h.btnOutline.setImageAlpha(200)
        h.btnCategory.setImageAlpha(200)
        h.btnDelete.setImageAlpha(200)
        h.btnPin.setImageAlpha(if (s.pinned) 255 else 200)
        h.btnFavorite.setImageAlpha(if (s.favorite) 255 else 200)
        h.btnOutline.setOnClickListener { listener?.onGenerateOutline(s) }
        h.btnCategory.setOnClickListener { listener?.onSetCategory(s) }
        h.itemView.setOnClickListener { listener?.onOpen(s) }
        h.btnPin.setOnClickListener { listener?.onTogglePin(s) }
        h.btnFavorite.setOnClickListener { listener?.onToggleFavorite(s) }
        h.btnDelete.setOnClickListener { listener?.onDelete(s) }
    }

    override fun getItemCount(): Int = rows.size

    class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.textCategoryTitle)
        val expand: ImageView = itemView.findViewById(R.id.iconCategoryExpand)
    }

    class ItemHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.textConversationTitle)
        val time: TextView = itemView.findViewById(R.id.textConversationTime)
        val outline: TextView? = itemView.findViewById(R.id.textConversationOutline)
        val btnOutline: ImageButton = itemView.findViewById(R.id.btnOutline)
        val btnCategory: ImageButton = itemView.findViewById(R.id.btnCategory)
        val btnPin: ImageButton = itemView.findViewById(R.id.btnPin)
        val btnFavorite: ImageButton = itemView.findViewById(R.id.btnFavorite)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)
    }
}
