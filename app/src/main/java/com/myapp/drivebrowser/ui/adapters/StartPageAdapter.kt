package com.myapp.drivebrowser.ui.adapters

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.myapp.drivebrowser.R
import com.myapp.drivebrowser.data.SiteIconCache

/**
 * Start-page quick-link grid. Shows a fixed set of slots; a slot with a blank URL renders as an
 * "Add" placeholder. Tapping a filled slot opens it; tapping an empty one (or long-pressing any)
 * triggers editing.
 */
class StartPageAdapter(
    private val onOpen: (String) -> Unit,
    private val onEdit: (Int) -> Unit
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
        val ctx = holder.itemView.context
        if (slot.url.isBlank()) {
            holder.icon.setImageResource(R.drawable.ic_add)
            holder.hint.text = ""
            holder.label.text = ctx.getString(R.string.empty_slot)
            holder.url.text = ctx.getString(R.string.empty_slot_hint)
            holder.itemView.setOnClickListener { onEdit(position) }
        } else {
            val icon = SiteIconCache.load(slot.url)
            if (icon != null) holder.icon.setImageBitmap(icon) else holder.icon.setImageResource(R.drawable.ic_public)
            holder.hint.text = hostHint(slot.url)
            holder.label.text = slot.label.ifBlank { hostHint(slot.url) }
            holder.url.text = slot.url
            holder.itemView.setOnClickListener { onOpen(slot.url) }
        }
        holder.itemView.setOnLongClickListener { onEdit(position); true }
    }

    private fun hostHint(url: String): String {
        val host = Uri.parse(url).host ?: url
        return if (host.length > 6) host.take(6) + "…" else host
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.slotIcon)
        val hint: TextView = v.findViewById(R.id.slotHint)
        val label: TextView = v.findViewById(R.id.slotLabel)
        val url: TextView = v.findViewById(R.id.slotUrl)
    }
}
