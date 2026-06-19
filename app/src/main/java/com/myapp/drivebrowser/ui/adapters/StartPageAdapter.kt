package com.myapp.drivebrowser.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.myapp.drivebrowser.R
import com.myapp.drivebrowser.data.SiteIconCache

class StartPageAdapter(
    private val onOpen: (String) -> Unit
) : RecyclerView.Adapter<StartPageAdapter.VH>() {

    data class Slot(val label: String, val url: String)

    private val items = mutableListOf<Slot>()

    fun submit(list: List<Slot>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_start_page_slot, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val slot = items[position]
        holder.label.text = slot.label
        val icon = SiteIconCache.load(slot.url)
        if (icon != null) holder.icon.setImageBitmap(icon)
        else holder.icon.setImageResource(R.drawable.ic_public)
        holder.itemView.setOnClickListener { onOpen(slot.url) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.slotIcon)
        val label: TextView = v.findViewById(R.id.slotLabel)
    }
}
