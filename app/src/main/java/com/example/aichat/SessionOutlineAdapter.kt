package com.example.aichat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.ArrayList

class SessionOutlineAdapter : RecyclerView.Adapter<SessionOutlineAdapter.OutlineHolder>() {

    interface OnItemActionListener {
        fun onEdit(item: SessionOutlineItem)
        fun onDelete(item: SessionOutlineItem)
        /** Volume 类型才会触发：用户拨动 selected 开关时回调。 */
        fun onSelectedChanged(item: SessionOutlineItem, selected: Boolean) {}
    }

    private val items: MutableList<SessionOutlineItem> = ArrayList()
    private var listener: OnItemActionListener? = null

    fun setOnItemActionListener(listener: OnItemActionListener) {
        this.listener = listener
    }

    fun setItems(list: List<SessionOutlineItem>?) {
        items.clear()
        if (list != null) items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OutlineHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_session_outline, parent, false)
        return OutlineHolder(v)
    }

    override fun onBindViewHolder(holder: OutlineHolder, position: Int) {
        if (position < 0 || position >= items.size) return
        val item = items[position]
        holder.textType.text = displayType(item.type)
        holder.textTitle.text = safe(item.title)
        holder.textContent.text = safe(item.content)
        holder.btnEdit.setOnClickListener { listener?.onEdit(item) }
        holder.btnDelete.setOnClickListener { listener?.onDelete(item) }
        // selected 开关：仅 volume 显示
        if (item.type == "volume") {
            holder.switchSelected.visibility = View.VISIBLE
            holder.switchSelected.setOnCheckedChangeListener(null)
            holder.switchSelected.isChecked = item.selected
            holder.switchSelected.setOnCheckedChangeListener { _, checked ->
                item.selected = checked
                listener?.onSelectedChanged(item, checked)
            }
        } else {
            holder.switchSelected.setOnCheckedChangeListener(null)
            holder.switchSelected.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = items.size

    class OutlineHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textType: TextView = itemView.findViewById(R.id.textOutlineType)
        val textTitle: TextView = itemView.findViewById(R.id.textOutlineTitle)
        val textContent: TextView = itemView.findViewById(R.id.textOutlineContent)
        val btnEdit: View = itemView.findViewById(R.id.btnOutlineEdit)
        val btnDelete: View = itemView.findViewById(R.id.btnOutlineDelete)
        val switchSelected: MaterialSwitch = itemView.findViewById(R.id.switchOutlineSelected)
    }

    private fun safe(text: String?): String = text ?: ""

    private fun displayType(type: String): String {
        return when (type) {
            "material" -> "资料"
            "task" -> "人物资料"
            "world" -> "世界背景"
            "knowledge" -> "知情约束"
            "volume" -> "卷大纲"
            else -> "章节"
        }
    }
}
