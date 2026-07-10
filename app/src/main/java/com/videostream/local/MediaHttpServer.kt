package com.videostream.local

import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import fi.iki.elonen.NanoHTTPD
import java.io.FileInputStream

/**
 * Serves one or more existing video files over HTTP with byte-range support, so a
 * browser or media player on another device can start playing and seek without
 * downloading the whole file first. When there's more than one [VideoEntry] (a
 * folder was chosen), the index page lists them so the viewer can pick which one
 * to watch; with a single entry it goes straight to the player.
 */
class MediaHttpServer(
    port: Int,
    private val contentResolver: ContentResolver,
    private val entries: List<VideoEntry>,
    private val libraryName: String
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
            "/", "/index.html" -> serveIndex()
            "/watch" -> serveWatch(session)
            "/video" -> serveVideo(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun entryFor(session: IHTTPSession): VideoEntry? {
        val id = session.parameters["id"]?.firstOrNull()?.toIntOrNull() ?: return null
        return entries.firstOrNull { it.id == id }
    }

    private fun serveIndex(): Response {
        val single = entries.singleOrNull()
        if (single != null) {
            return watchPage(single)
        }
        if (entries.isEmpty()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No videos available")
        }
        val items = entries.joinToString("\n") { entry ->
            "<li><a href=\"/watch?id=${entry.id}\">${escapeHtml(entry.name)}</a></li>"
        }
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>${escapeHtml(libraryName)}</title>
              <style>
                body { margin: 0; padding: 24px; background: #111; color: #eee; font-family: sans-serif; }
                h1 { font-size: 20px; }
                ul { list-style: none; padding: 0; }
                li { margin: 4px 0; }
                a { display: block; padding: 12px 16px; background: #222; color: #fff; text-decoration: none; border-radius: 8px; }
                a:hover { background: #333; }
              </style>
            </head>
            <body>
              <h1>${escapeHtml(libraryName)}</h1>
              <ul>
                $items
              </ul>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun serveWatch(session: IHTTPSession): Response {
        val entry = entryFor(session)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not found")
        return watchPage(entry)
    }

    private fun watchPage(entry: VideoEntry): Response {
        val backLink = if (entries.size > 1) {
            "<p><a class=\"back\" href=\"/\">&larr; ${escapeHtml(libraryName)}</a></p>"
        } else {
            ""
        }
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>${escapeHtml(entry.name)}</title>
              <style>
                body { margin: 0; background: #111; color: #eee; font-family: sans-serif; }
                .back { display: inline-block; margin: 12px 16px; color: #9cf; text-decoration: none; }
                .player { display: flex; align-items: center; justify-content: center; height: ${if (backLink.isEmpty()) "100vh" else "calc(100vh - 48px)"}; }
                video { max-width: 100%; max-height: 100%; }
              </style>
            </head>
            <body>
              $backLink
              <div class="player">
                <video controls autoplay src="/video?id=${entry.id}"></video>
              </div>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun serveVideo(session: IHTTPSession): Response {
        val entry = entryFor(session)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not found")

        val pfd = try {
            contentResolver.openFileDescriptor(entry.uri, "r")
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

        val mimeType = contentResolver.getType(entry.uri) ?: "video/mp4"
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

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
