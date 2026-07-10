package com.videostream.local

import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves one or more existing video files over HTTP with byte-range support, so a
 * browser or media player on another device can start playing and seek without
 * downloading the whole file first. In folder mode, `/browse` mirrors the chosen
 * folder's actual directory structure (subfolders navigate further, matching entries'
 * [VideoEntry.folderPath]) rather than flattening everything into one list; a plain
 * single file goes straight to the player.
 */
class MediaHttpServer(
    port: Int,
    private val contentResolver: ContentResolver,
    private val entries: List<VideoEntry>,
    private val libraryName: String,
    private val isFolderMode: Boolean
) : NanoHTTPD(port) {

    // Keyed by VideoEntry.id. An empty array means extraction was already tried and failed,
    // so a broken/DRM'd file isn't re-decoded on every thumbnail request.
    private val thumbnailCache = ConcurrentHashMap<Int, ByteArray>()

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

    /** How the videos in [browsePage] are ordered; switched via the `sort` query param. */
    private enum class SortMode(val param: String, val label: String) {
        NAME("name", "Name"),
        DATE("date", "Newest"),
        SIZE("size", "Largest");

        companion object {
            fun fromParam(value: String?): SortMode = values().firstOrNull { it.param == value } ?: NAME
        }
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/", "/index.html" -> serveIndex()
            "/browse" -> browsePage(
                session.parameters["path"]?.firstOrNull().orEmpty(),
                SortMode.fromParam(session.parameters["sort"]?.firstOrNull())
            )
            "/watch" -> serveWatch(session)
            "/video" -> serveVideo(session)
            "/thumbnail" -> serveThumbnail(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun entryFor(session: IHTTPSession): VideoEntry? {
        val id = session.parameters["id"]?.firstOrNull()?.toIntOrNull() ?: return null
        return entries.firstOrNull { it.id == id }
    }

    private fun serveIndex(): Response {
        if (!isFolderMode) {
            val single = entries.singleOrNull()
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No video available")
            return watchPage(single, SortMode.NAME)
        }
        return browsePage("", SortMode.NAME)
    }

    private fun serveWatch(session: IHTTPSession): Response {
        val entry = entryFor(session)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not found")
        val sortMode = SortMode.fromParam(session.parameters["sort"]?.firstOrNull())
        return watchPage(entry, sortMode)
    }

    private fun sortVideos(videos: List<VideoEntry>, sortMode: SortMode): List<VideoEntry> = when (sortMode) {
        SortMode.NAME -> videos.sortedBy { it.name.lowercase() }
        SortMode.DATE -> videos.sortedByDescending { it.lastModified }
        SortMode.SIZE -> videos.sortedByDescending { it.sizeBytes }
    }

    /** Renders the videos and immediate subfolders that live directly inside [path]. */
    private fun browsePage(path: String, sortMode: SortMode): Response {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val videos = sortVideos(entries.filter { it.folderPath == path }, sortMode)
        val subfolders = entries
            .filter { it.folderPath != path && it.folderPath.startsWith(prefix) }
            .map { it.folderPath.removePrefix(prefix).substringBefore('/') }
            .distinct()
            .sorted()

        if (videos.isEmpty() && subfolders.isEmpty()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Folder not found")
        }

        val title = if (path.isEmpty()) libraryName else path.substringAfterLast('/')
        val backLink = if (path.isNotEmpty()) {
            val parentPath = path.substringBeforeLast('/', "")
            "<li><a href=\"/browse?path=${encodePath(parentPath)}&sort=${sortMode.param}\">&larr; ..</a></li>"
        } else {
            ""
        }
        val folderItems = subfolders.joinToString("\n") { folderName ->
            val childPath = if (path.isEmpty()) folderName else "$path/$folderName"
            "<li><a href=\"/browse?path=${encodePath(childPath)}&sort=${sortMode.param}\">&#128193; ${escapeHtml(folderName)}</a></li>"
        }
        val videoItems = videos.joinToString("\n") { entry ->
            """
            <li>
              <a href="/watch?id=${entry.id}&sort=${sortMode.param}">
                <img src="/thumbnail?id=${entry.id}" loading="lazy" alt="">
                <span>${escapeHtml(entry.name)}</span>
              </a>
            </li>
            """.trimIndent()
        }
        val sortLinks = SortMode.values().joinToString(" ") { mode ->
            if (mode == sortMode) {
                "<span class=\"active\">${mode.label}</span>"
            } else {
                "<a href=\"/browse?path=${encodePath(path)}&sort=${mode.param}\">${mode.label}</a>"
            }
        }
        val sortBar = if (videos.size > 1) {
            "<div class=\"sortbar\"><span class=\"label\">Sort:</span> $sortLinks</div>"
        } else {
            ""
        }

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>${escapeHtml(title)}</title>
              <style>
                body { margin: 0; padding: 24px; background: #111; color: #eee; font-family: sans-serif; }
                h1 { font-size: 20px; }
                .sortbar { margin: 0 0 16px; font-size: 13px; }
                .sortbar .label { color: #888; margin-right: 8px; }
                .sortbar a, .sortbar .active { margin-right: 12px; text-decoration: none; }
                .sortbar a { color: #9cf; }
                .sortbar .active { color: #fff; font-weight: bold; }
                ul.folders { list-style: none; padding: 0; margin: 0 0 16px; }
                ul.folders li { margin: 4px 0; }
                ul.folders a { display: block; padding: 12px 16px; background: #222; color: #fff; text-decoration: none; border-radius: 8px; }
                ul.folders a:hover { background: #333; }
                ul.videos { list-style: none; padding: 0; margin: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 12px; }
                ul.videos a { display: flex; flex-direction: column; background: #222; color: #fff; text-decoration: none; border-radius: 8px; overflow: hidden; }
                ul.videos a:hover { background: #333; }
                ul.videos img { width: 100%; aspect-ratio: 16 / 9; object-fit: cover; background: #000; }
                ul.videos span { padding: 8px; font-size: 13px; word-break: break-word; }
              </style>
            </head>
            <body>
              <h1>${escapeHtml(title)}</h1>
              <ul class="folders">
                $backLink
                $folderItems
              </ul>
              $sortBar
              <ul class="videos">
                $videoItems
              </ul>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun watchPage(entry: VideoEntry, sortMode: SortMode): Response {
        val backLink = if (isFolderMode) {
            "<p><a class=\"back\" href=\"/browse?path=${encodePath(entry.folderPath)}&sort=${sortMode.param}\">&larr; Back</a></p>"
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
                <video controls autoplay poster="/thumbnail?id=${entry.id}" src="/video?id=${entry.id}"></video>
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

    private fun serveThumbnail(session: IHTTPSession): Response {
        val entry = entryFor(session)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not found")

        val jpeg = thumbnailCache.getOrPut(entry.id) {
            ThumbnailUtil.extractThumbnailJpeg(contentResolver, entry.uri) ?: ByteArray(0)
        }
        return if (jpeg.isNotEmpty()) {
            newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(jpeg), jpeg.size.toLong())
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No thumbnail available")
        }
    }

    private fun encodePath(path: String): String = URLEncoder.encode(path, "UTF-8")

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
