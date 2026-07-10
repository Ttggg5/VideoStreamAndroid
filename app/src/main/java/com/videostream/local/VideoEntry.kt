package com.videostream.local

import android.net.Uri

/**
 * One playable video, either the single file chosen by the user or one found inside a
 * chosen folder. [folderPath] is the entry's directory relative to the chosen root,
 * using '/' as a separator and "" for the root itself — it's what lets the web UI
 * browse the folder the same way it's laid out on disk instead of flattening everything
 * into one list.
 */
data class VideoEntry(
    val id: Int,
    val name: String,
    val folderPath: String,
    val uri: Uri
)
