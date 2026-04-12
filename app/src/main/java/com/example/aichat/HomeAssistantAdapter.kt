package com.example.aichat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.ArrayList

class HomeAssistantAdapter : RecyclerView.Adapter<HomeAssistantAdapter.Holder>() {

    private var items: List<MyAssistant> = ArrayList()
    private var listener: OnAssistantClickListener? = null

    fun interface OnAssistantClickListener {
        fun onAssistantClick(assistant: MyAssistant)
    }

    fun setOnAssistantClickListener(listener: OnAssistantClickListener) {
        this.listener = listener
    }

    fun setItems(list: List<MyAssistant>?) {
        items = list ?: ArrayList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_home_assistant, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val a = items[position] ?: return
        val n = if (a.name != null && a.name.isNotEmpty()) a.name else "助手"
        holder.name.setText(n)
        AssistantAvatarHelper.bindAvatar(holder.avatarImage, holder.avatar, a, n)
        holder.itemView.setOnClickListener { listener?.onAssistantClick(a) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatarImage: ImageView = itemView.findViewById(R.id.imageAvatar)
        val avatar: TextView = itemView.findViewById(R.id.textAvatar)
        val name: TextView = itemView.findViewById(R.id.textName)
    }
}
