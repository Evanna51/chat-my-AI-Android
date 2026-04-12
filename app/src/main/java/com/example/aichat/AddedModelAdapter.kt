package com.example.aichat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.util.ArrayList

class AddedModelAdapter : RecyclerView.Adapter<AddedModelAdapter.Holder>() {

    private var items: List<ProviderInfo.ProviderModelInfo> = ArrayList()
    private var listener: OnModelActionListener? = null

    interface OnModelActionListener {
        fun onEditAlias(model: ProviderInfo.ProviderModelInfo)
        fun onRemove(model: ProviderInfo.ProviderModelInfo)
    }

    fun setOnModelActionListener(l: OnModelActionListener) {
        listener = l
    }

    fun setModels(list: List<ProviderInfo.ProviderModelInfo>?) {
        items = if (list != null) ArrayList(list) else ArrayList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model_added, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val m = items[position]
        val display = if (m.nickname != null && m.nickname.isNotEmpty()) "${m.nickname} (${m.modelId})" else m.modelId
        h.modelDisplay.setText(display)
        h.btnEditAlias.setOnClickListener { listener?.onEditAlias(m) }
        h.btnRemove.setOnClickListener { listener?.onRemove(m) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val modelDisplay: TextView = v.findViewById(R.id.modelDisplay)
        val btnEditAlias: MaterialButton = v.findViewById(R.id.btnEditAlias)
        val btnRemove: MaterialButton = v.findViewById(R.id.btnRemove)
    }
}
