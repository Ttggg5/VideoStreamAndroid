package com.videostream.local

import android.content.ContentResolver
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Extracts a small preview frame from a video file, for use as a thumbnail. */
object ThumbnailUtil {
    private const val MAX_DIMENSION = 320
    private const val JPEG_QUALITY = 80
    private const val PREVIEW_FRAME_TIME_US = 1_000_000L // 1 second in

    /** Returns a downscaled JPEG frame from [uri], or null if the file has no readable video track. */
    fun extractThumbnailJpeg(contentResolver: ContentResolver, uri: Uri): ByteArray? {
        val pfd = try {
            contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        } ?: return null

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(pfd.fileDescriptor)
            val frame = retriever.getFrameAtTime(PREVIEW_FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime()
                ?: return null
            val scaled = downscale(frame)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            scaled.recycle()
            if (scaled !== frame) frame.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
            pfd.close()
        }
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        if (maxDim <= MAX_DIMENSION) return bitmap
        val scale = MAX_DIMENSION.toFloat() / maxDim
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
