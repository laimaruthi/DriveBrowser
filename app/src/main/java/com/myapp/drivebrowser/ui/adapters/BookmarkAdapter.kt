package com.myapp.drivebrowser.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.myapp.drivebrowser.R
import com.myapp.drivebrowser.data.BrowserPreferences.Bookmark

class BookmarkAdapter(
    private val onOpen: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<BookmarkAdapter.VH>() {

    private val items = mutableListOf<Bookmark>()

    fun submit(list: List<Bookmark>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = items[position]
        holder.title.text = b.title
        holder.url.text = b.url
        holder.itemView.setOnClickListener { onOpen(b.url) }
        holder.delete.setOnClickListener { onDelete(b.url) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.bookmarkTitle)
        val url: TextView = v.findViewById(R.id.bookmarkUrl)
        val delete: ImageButton = v.findViewById(R.id.bookmarkDelete)
    }
}
