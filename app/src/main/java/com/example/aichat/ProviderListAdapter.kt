package com.example.aichat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.ArrayList

class ProviderListAdapter : RecyclerView.Adapter<ProviderListAdapter.Holder>() {

    private var items: List<ProviderInfo> = ArrayList()
    private var listener: OnProviderClickListener? = null

    fun interface OnProviderClickListener {
        fun onProviderClick(p: ProviderInfo)
    }

    fun setOnProviderClickListener(l: OnProviderClickListener) {
        listener = l
    }

    fun setProviders(list: List<ProviderInfo>?) {
        items = list ?: ArrayList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_provider, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val p = items[position]
        h.name.setText(p.name)
        h.itemView.setOnClickListener { listener?.onProviderClick(p) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.providerName)
    }
}
