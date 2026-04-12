package com.example.aichat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.ArrayList
import java.util.HashSet

class ProviderCatalogAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    private val items: MutableList<Any> = ArrayList()
    private val enabledIds: MutableSet<String> = HashSet()
    private var listener: OnItemClickListener? = null

    fun interface OnItemClickListener {
        fun onItemClick(item: ProviderCatalog.CatalogItem)
    }

    fun setOnItemClickListener(l: OnItemClickListener) { listener = l }

    fun setData(catalog: List<ProviderCatalog.CatalogItem>, enabled: Set<String>?) {
        items.clear()
        enabledIds.clear()
        if (enabled != null) enabledIds.addAll(enabled)
        var lastCat: String? = null
        for (item in catalog) {
            if (item.category != lastCat) {
                lastCat = item.category
                items.add(lastCat!!)
            }
            items.add(item)
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position] is String) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_provider_catalog_header, parent, false)
            HeaderHolder(v)
        } else {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_provider_catalog, parent, false)
            ItemHolder(v)
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        val o = items[position]
        if (h is HeaderHolder) {
            h.title.setText(o as String)
        } else {
            val item = o as ProviderCatalog.CatalogItem
            val ih = h as ItemHolder
            ih.name.setText(item.name)
            ih.added.setVisibility(if (enabledIds.contains(item.id)) View.VISIBLE else View.GONE)
            ih.itemView.setOnClickListener { listener?.onItemClick(item) }
        }
    }

    override fun getItemCount(): Int = items.size

    class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.headerTitle)
    }

    class ItemHolder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.providerName)
        val added: TextView = v.findViewById(R.id.labelAdded)
    }
}
