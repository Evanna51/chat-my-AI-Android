package com.example.aichat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.util.ArrayList
import java.util.HashSet

class AvailableModelAdapter : RecyclerView.Adapter<AvailableModelAdapter.Holder>() {

    private var items: List<ProviderInfo.ProviderModelInfo> = ArrayList()
    private var addedIds: Set<String> = HashSet()
    private var listener: OnAddListener? = null

    fun interface OnAddListener {
        fun onAdd(model: ProviderInfo.ProviderModelInfo)
    }

    fun setOnAddListener(l: OnAddListener) {
        listener = l
    }

    fun setItems(list: List<ProviderInfo.ProviderModelInfo>?, alreadyAdded: Set<String>?) {
        items = if (list != null) ArrayList(list) else ArrayList()
        addedIds = if (alreadyAdded != null) HashSet(alreadyAdded) else HashSet()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model_available, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val m = items[position]
        h.modelId.setText(m.modelId)
        val alreadyAdded = addedIds.contains(m.modelId)
        h.btnAdd.setEnabled(!alreadyAdded)
        h.btnAdd.setText(if (alreadyAdded) "已添加" else "添加")
        h.btnAdd.setOnClickListener { if (!alreadyAdded) listener?.onAdd(m) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val modelId: TextView = v.findViewById(R.id.modelId)
        val btnAdd: MaterialButton = v.findViewById(R.id.btnAdd)
    }
}
