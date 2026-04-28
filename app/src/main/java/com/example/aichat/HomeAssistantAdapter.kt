package com.example.aichat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.ArrayList

class HomeAssistantAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_ASSISTANT = 0
        private const val VIEW_TYPE_MORE = 1
    }

    private var items: List<MyAssistant> = ArrayList()
    private var listener: OnAssistantClickListener? = null
    private var moreListener: OnMoreClickListener? = null
    private var moreLabelRes: Int = R.string.home_assistant_more
    private var subtitleResolver: (MyAssistant) -> String = { defaultSubtitleFor(it) }

    fun interface OnAssistantClickListener {
        fun onAssistantClick(assistant: MyAssistant)
    }

    fun interface OnMoreClickListener {
        fun onMoreClick()
    }

    fun setOnAssistantClickListener(listener: OnAssistantClickListener) {
        this.listener = listener
    }

    fun setOnMoreClickListener(listener: OnMoreClickListener) {
        this.moreListener = listener
    }

    fun setMoreLabelRes(resId: Int) {
        this.moreLabelRes = resId
        notifyDataSetChanged()
    }

    fun setItems(list: List<MyAssistant>?) {
        items = list ?: ArrayList()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (position == items.size) VIEW_TYPE_MORE else VIEW_TYPE_ASSISTANT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_MORE) {
            MoreHolder(inflater.inflate(R.layout.item_home_assistant_more, parent, false))
        } else {
            AssistantHolder(inflater.inflate(R.layout.item_home_assistant, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AssistantHolder -> bindAssistant(holder, items[position])
            is MoreHolder -> bindMore(holder)
        }
    }

    private fun bindAssistant(holder: AssistantHolder, a: MyAssistant) {
        val name = if (a.name.isNotEmpty()) a.name else "助手"
        holder.name.text = name
        holder.subtitle.text = subtitleResolver(a)
        holder.subtitle.visibility = if (holder.subtitle.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        AssistantAvatarHelper.bindAvatar(holder.avatarImage, holder.avatar, a, name)
        holder.itemView.setOnClickListener { listener?.onAssistantClick(a) }
    }

    private fun bindMore(holder: MoreHolder) {
        holder.icon.contentDescription = holder.itemView.context.getString(moreLabelRes)
        holder.itemView.setOnClickListener { moreListener?.onMoreClick() }
    }

    override fun getItemCount(): Int = items.size + 1

    private fun defaultSubtitleFor(a: MyAssistant): String {
        return when (a.type) {
            "writer" -> "作家"
            "character" -> "角色"
            else -> "通用"
        }
    }

    class AssistantHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatarImage: ImageView = itemView.findViewById(R.id.imageAvatar)
        val avatar: TextView = itemView.findViewById(R.id.textAvatar)
        val name: TextView = itemView.findViewById(R.id.textName)
        val subtitle: TextView = itemView.findViewById(R.id.textSubtitle)
    }

    class MoreHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.iconMore)
    }
}
