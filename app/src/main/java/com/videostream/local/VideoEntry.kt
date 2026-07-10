package com.videostream.local

import android.net.Uri

/** One playable video, either the single file chosen by the user or one found inside a chosen folder. */
data class VideoEntry(
    val id: Int,
    val name: String,
    val uri: Uri
)
