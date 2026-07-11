package com.videostream.local

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** One row/cell in the native folder-browsing grid — either a subfolder or a playable video. */
sealed class BrowseItem {
    data class Folder(val name: String, val path: String) : BrowseItem()
    data class Video(val video: RemoteVideo, val subtitle: String?) : BrowseItem()
}

/**
 * Shows [BrowseItem.Folder]s as full-width rows and [BrowseItem.Video]s as thumbnail grid
 * cells in the same [RecyclerView] — the native equivalent of [browsePage]'s `ul.folders` list
 * above a `ul.videos` grid. Pair with [spanSizeLookup] on a [GridLayoutManager] so folder rows
 * span every column instead of being squeezed into one grid cell.
 */
class BrowseAdapter(
    private val baseUrl: String,
    private val onFolderClick: (BrowseItem.Folder) -> Unit,
    private val onVideoClick: (BrowseItem.Video) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<BrowseItem> = emptyList()

    fun submitList(newItems: List<BrowseItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    /** Attach to a [GridLayoutManager] so folder rows occupy the full width, not one cell. */
    fun spanSizeLookup(spanCount: Int): GridLayoutManager.SpanSizeLookup =
        object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (items.getOrNull(position) is BrowseItem.Folder) spanCount else 1
        }

    private class FolderViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.folderName)
    }

    private class VideoViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val thumbnail: ImageView = itemView.findViewById(R.id.videoThumbnail)
        val title: TextView = itemView.findViewById(R.id.videoTitle)
        val subtitle: TextView = itemView.findViewById(R.id.videoSubtitle)
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is BrowseItem.Folder -> VIEW_TYPE_FOLDER
        is BrowseItem.Video -> VIEW_TYPE_VIDEO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_FOLDER) {
            FolderViewHolder(inflater.inflate(R.layout.item_browse_folder, parent, false))
        } else {
            VideoViewHolder(inflater.inflate(R.layout.item_browse_video, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is BrowseItem.Folder -> {
                holder as FolderViewHolder
                holder.name.text = item.name
                holder.itemView.setOnClickListener { onFolderClick(item) }
            }
            is BrowseItem.Video -> {
                holder as VideoViewHolder
                holder.title.text = item.video.name
                if (item.subtitle != null) {
                    holder.subtitle.text = item.subtitle
                    holder.subtitle.visibility = android.view.View.VISIBLE
                } else {
                    holder.subtitle.visibility = android.view.View.GONE
                }
                ThumbnailLoader.load(holder.thumbnail, "$baseUrl/thumbnail?id=${item.video.id}")
                holder.itemView.setOnClickListener { onVideoClick(item) }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    companion object {
        private const val VIEW_TYPE_FOLDER = 0
        private const val VIEW_TYPE_VIDEO = 1
    }
}
