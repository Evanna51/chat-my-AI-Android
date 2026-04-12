package com.example.aichat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.ArrayList

class ModelListAdapter : RecyclerView.Adapter<ModelListAdapter.Holder>() {

    private var items: List<ProviderInfo.ProviderModelInfo> = ArrayList()

    fun setModels(list: List<ProviderInfo.ProviderModelInfo>?) {
        items = list ?: ArrayList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model_simple, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val m = items[position]
        h.text.setText(if (m.nickname != null && m.nickname.isNotEmpty()) m.nickname else m.modelId)
    }

    override fun getItemCount(): Int = items.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.modelId)
    }
}
