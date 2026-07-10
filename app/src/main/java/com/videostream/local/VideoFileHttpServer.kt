package com.videostream.local

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import fi.iki.elonen.NanoHTTPD
import java.io.FileInputStream

/**
 * Serves a single existing video file (identified by a content [Uri]) over
 * HTTP with byte-range support, so a browser or media player on another
 * device can start playing and seek without downloading the whole file
 * first.
 */
class VideoFileHttpServer(
    port: Int,
    private val contentResolver: ContentResolver,
    private val videoUri: Uri,
    private val displayName: String
) : NanoHTTPD(port) {

    /** Closes the underlying [ParcelFileDescriptor] together with the stream view over it. */
    private class ClosingFileInputStream(private val pfd: ParcelFileDescriptor) :
        FileInputStream(pfd.fileDescriptor) {
        override fun close() {
            try {
                super.close()
            } finally {
                pfd.close()
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/", "/index.html" -> newFixedLengthResponse(Response.Status.OK, "text/html", indexHtml())
            "/video" -> serveVideo(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun serveVideo(session: IHTTPSession): Response {
        val pfd = try {
            contentResolver.openFileDescriptor(videoUri, "r")
        } catch (e: Exception) {
            null
        } ?: return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR, "text/plain", "Cannot open video file"
        )

        val fileSize = pfd.statSize
        if (fileSize <= 0) {
            pfd.close()
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Unknown file size")
        }

        val mimeType = contentResolver.getType(videoUri) ?: "video/mp4"
        val rangeHeader = session.headers["range"]

        var start = 0L
        var end = fileSize - 1
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val parts = rangeHeader.removePrefix("bytes=").split("-")
            parts.getOrNull(0)?.takeIf { it.isNotBlank() }?.toLongOrNull()?.let { start = it }
            parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLongOrNull()?.let { end = it }
        }
        start = start.coerceIn(0, fileSize - 1)
        end = end.coerceIn(start, fileSize - 1)
        val contentLength = end - start + 1

        val stream = ClosingFileInputStream(pfd)
        var skipped = 0L
        while (skipped < start) {
            val n = stream.skip(start - skipped)
            if (n <= 0) break
            skipped += n
        }

        val status = if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val response = newFixedLengthResponse(status, mimeType, stream, contentLength)
        response.addHeader("Accept-Ranges", "bytes")
        if (rangeHeader != null) {
            response.addHeader("Content-Range", "bytes $start-$end/$fileSize")
        }
        return response
    }

    private fun indexHtml(): String = """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>${escapeHtml(displayName)}</title>
          <style>
            body { margin: 0; background: #111; display: flex; align-items: center; justify-content: center; height: 100vh; }
            video { max-width: 100%; max-height: 100%; }
          </style>
        </head>
        <body>
          <video controls autoplay src="/video"></video>
        </body>
        </html>
    """.trimIndent()

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
