package com.example.aichat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.ArrayList

class MyAssistantListAdapter : RecyclerView.Adapter<MyAssistantListAdapter.Holder>() {

    private var items: List<MyAssistant> = ArrayList()
    private var listener: OnAssistantClickListener? = null

    fun interface OnAssistantClickListener {
        fun onClick(assistant: MyAssistant)
    }

    fun setOnAssistantClickListener(listener: OnAssistantClickListener) {
        this.listener = listener
    }

    fun setItems(list: List<MyAssistant>?) {
        items = list ?: ArrayList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_my_assistant, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val a = items[position] ?: return
        val name = if (a.name != null && a.name.isNotEmpty()) a.name else "未命名助手"
        holder.name.setText(name)
        AssistantAvatarHelper.bindAvatar(holder.avatarImage, holder.avatarText, a, name)
        val typeLabel = when {
            "writer" == a.type -> "作家"
            "character" == a.type -> "人物"
            else -> "默认"
        }
        holder.type.setText(typeLabel)
        holder.promptPreview.setText(if (a.prompt != null && a.prompt.isNotEmpty()) a.prompt else "无设定")
        holder.itemView.setOnClickListener { listener?.onClick(a) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatarImage: ImageView = itemView.findViewById(R.id.imageAssistantAvatar)
        val avatarText: TextView = itemView.findViewById(R.id.textAssistantAvatar)
        val name: TextView = itemView.findViewById(R.id.textAssistantName)
        val type: TextView = itemView.findViewById(R.id.textAssistantType)
        val promptPreview: TextView = itemView.findViewById(R.id.textAssistantPromptPreview)
    }
}
