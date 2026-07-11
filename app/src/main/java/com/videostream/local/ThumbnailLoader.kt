package com.videostream.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Loads a host's `/thumbnail?id=…` images into [ImageView]s for the native folder-browsing
 * screen, since these come from the network instead of a local file (unlike [ThumbnailUtil],
 * which extracts a frame from a file this device already has open). Small in-memory cache plus
 * a tag-based staleness check keep a recycled `RecyclerView` row from flashing the wrong
 * thumbnail while a slower load from an earlier bind is still in flight.
 */
object ThumbnailLoader {
    private const val MAX_CACHE_BYTES = 8 * 1024 * 1024 // 8MB of decoded bitmaps
    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Loads [url] into [imageView], clearing any previous image while the new one loads. */
    fun load(imageView: ImageView, url: String) {
        imageView.tag = url
        val cached = cache.get(url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }
        imageView.setImageDrawable(null)
        executor.execute {
            val bitmap = download(url)
            mainHandler.post {
                // The view may have been recycled for a different item while this load was
                // in flight — only apply the result if it's still the one that was asked for.
                if (imageView.tag == url && bitmap != null) {
                    cache.put(url, bitmap)
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun download(url: String): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
