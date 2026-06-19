package com.myapp.drivebrowser.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.myapp.drivebrowser.R
import com.myapp.drivebrowser.tabs.BrowserTab

class TabAdapter(
    private val onOpen: (Long) -> Unit,
    private val onClose: (Long) -> Unit
) : RecyclerView.Adapter<TabAdapter.VH>() {

    private val items = mutableListOf<BrowserTab>()

    fun submit(tabs: List<BrowserTab>) {
        items.clear(); items.addAll(tabs); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_browser_tab, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tab = items[position]
        holder.title.text = tab.title.ifBlank { tab.currentUrl.ifBlank { holder.itemView.context.getString(R.string.tab_blank_title) } }
        holder.itemView.setOnClickListener { onOpen(tab.id) }
        holder.close.setOnClickListener { onClose(tab.id) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tabTitle)
        val close: ImageButton = v.findViewById(R.id.tabClose)
    }
}
