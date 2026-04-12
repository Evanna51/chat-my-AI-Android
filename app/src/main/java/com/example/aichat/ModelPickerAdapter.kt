package com.example.aichat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ModelPickerAdapter(
    private val items: List<ConfiguredModelPicker.Option>,
    private val selectedKey: String?,
    private val listener: OnSelectListener?
) : RecyclerView.Adapter<ModelPickerAdapter.Holder>() {

    fun interface OnSelectListener {
        fun onSelect(option: ConfiguredModelPicker.Option)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model_picker, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val o = items[position] ?: return
        val display = o.displayName ?: ""
        val sub = "${o.modelId ?: ""} · ${o.providerName ?: ""}"
        h.modelDisplay.setText(display)
        h.modelSub.setText(sub)
        h.itemView.isSelected = selectedKey != null && selectedKey == o.getStorageKey()
        h.itemView.setOnClickListener { listener?.onSelect(o) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val modelDisplay: TextView = v.findViewById(R.id.modelDisplay)
        val modelSub: TextView = v.findViewById(R.id.modelSub)
    }
}
