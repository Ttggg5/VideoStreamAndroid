package com.videostream.local

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** One row/cell in the native folder-browsing grid — either a subfolder or a playable video. */
sealed class BrowseItem {
    data class Folder(val name: String, val path: String) : BrowseItem()
    data class Video(val video: RemoteVideo, val subtitle: String?) : BrowseItem()
}

/**
 * Shows [BrowseItem.Folder]s and [BrowseItem.Video]s as matching thumbnail-tile grid cells
 * (a 16:9 tile plus a title below — folders get a plain folder-icon tile in place of a real
 * thumbnail) in the same [RecyclerView], so the grid reads as one consistent style instead of
 * folders and videos looking like two different kinds of list.
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
