package com.videostream.local

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/** One video as reported by a host's `/api/browse`, mirroring [VideoEntry] minus the local [android.net.Uri]. */
data class RemoteVideo(
    val id: Int,
    val name: String,
    val folderPath: String,
    val type: String,
    val sizeBytes: Long,
    val lastModified: Long
)

/** What a host's `/api/browse?path=...` returned for that folder. */
data class RemoteBrowseResult(
    val effectivePath: String,
    val title: String,
    val videos: List<RemoteVideo>,
    val subfolders: List<String>
)

/** What a host's `/api/info` reported about itself, before deciding how to greet the viewer. */
data class RemoteLibraryInfo(
    val libraryName: String,
    val isFolderMode: Boolean,
    val defaultSort: String
)

/**
 * A host's current `/remote` pick and playback state, as polled from `/remote/state`. The
 * `*Revision` fields let a poller apply each play/pause/seek command exactly once — see
 * [MediaHttpServer]'s own `RemoteSelection` and the bare web player it drives, which
 * [WatchActivity]'s remote-follow mode mirrors natively.
 */
data class RemoteState(
    val videoId: Int?,
    val revision: Long,
    val playing: Boolean,
    val playRevision: Long,
    val seekSeconds: Double?,
    val seekRevision: Long
)

/**
 * Talks to a [MediaHttpServer]'s JSON endpoints (`/api/info`, `/api/browse`) so [WatchActivity]
 * can browse and play a host's library with native views instead of an embedded browser. Every
 * function here does blocking I/O and must be called off the main thread.
 */
object RemoteLibraryApi {
    private const val TIMEOUT_MS = 8000

    fun fetchInfo(baseUrl: String): RemoteLibraryInfo? {
        val json = getJson("$baseUrl/api/info") ?: return null
        return try {
            RemoteLibraryInfo(
                libraryName = json.getString("libraryName"),
                isFolderMode = json.getBoolean("isFolderMode"),
                defaultSort = json.getString("defaultSort")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun fetchBrowse(baseUrl: String, path: String, sort: String, flat: Boolean): RemoteBrowseResult? {
        val encodedPath = URLEncoder.encode(path, "UTF-8")
        val url = "$baseUrl/api/browse?path=$encodedPath&sort=$sort&flat=${if (flat) "1" else "0"}"
        val json = getJson(url) ?: return null
        return try {
            val videosJson = json.getJSONArray("videos")
            val videos = (0 until videosJson.length()).map { i ->
                val v = videosJson.getJSONObject(i)
                RemoteVideo(
                    id = v.getInt("id"),
                    name = v.getString("name"),
                    folderPath = v.getString("folderPath"),
                    type = v.getString("type"),
                    sizeBytes = v.getLong("sizeBytes"),
                    lastModified = v.getLong("lastModified")
                )
            }
            val subfoldersJson = json.getJSONArray("subfolders")
            val subfolders = (0 until subfoldersJson.length()).map { subfoldersJson.getString(it) }
            RemoteBrowseResult(
                effectivePath = json.getString("effectivePath"),
                title = json.getString("title"),
                videos = videos,
                subfolders = subfolders
            )
        } catch (e: Exception) {
            null
        }
    }

    fun fetchVideo(baseUrl: String, id: Int): RemoteVideo? {
        val json = getJson("$baseUrl/api/video?id=$id") ?: return null
        return try {
            RemoteVideo(
                id = json.getInt("id"),
                name = json.getString("name"),
                folderPath = json.getString("folderPath"),
                type = json.getString("type"),
                sizeBytes = json.getLong("sizeBytes"),
                lastModified = json.getLong("lastModified")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun fetchRemoteState(baseUrl: String): RemoteState? {
        val json = getJson("$baseUrl/remote/state") ?: return null
        return parseRemoteState(json)
    }

    /** Parses a `/remote/state` (or `/remote/ws` push) body — shared by [fetchRemoteState] and
     *  [RemoteStateSocket]'s WebSocket message handling. */
    fun parseRemoteState(text: String): RemoteState? = try {
        parseRemoteState(JSONObject(text))
    } catch (e: Exception) {
        null
    }

    private fun parseRemoteState(json: JSONObject): RemoteState? = try {
        RemoteState(
            videoId = if (json.isNull("videoId")) null else json.getInt("videoId"),
            revision = json.getLong("revision"),
            playing = json.getBoolean("playing"),
            playRevision = json.getLong("playRevision"),
            seekSeconds = if (json.isNull("seekSeconds")) null else json.getDouble("seekSeconds"),
            seekRevision = json.getLong("seekRevision")
        )
    } catch (e: Exception) {
        null
    }

    /** GETs [url] and parses the body as JSON, or null on any network/HTTP/parse failure. */
    private fun getJson(url: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            JSONObject(body)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
