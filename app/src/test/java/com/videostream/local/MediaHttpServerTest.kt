package com.videostream.local

import android.content.ContentResolver
import android.content.res.AssetManager
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Exercises [MediaHttpServer] over a real (loopback, ephemeral-port) HTTP connection —
 * NanoHTTPD itself has no Android dependency, so this runs as a plain JVM test. Only
 * [ContentResolver], [AssetManager], and [Uri] are Android framework types here, and
 * they're mocked with Mockito rather than touched for real, so no device/emulator is
 * needed to check the server's routing, sorting, folder navigation, and HTML escaping.
 */
class MediaHttpServerTest {

    private lateinit var contentResolver: ContentResolver
    private lateinit var assetManager: AssetManager
    private var server: MediaHttpServer? = null

    @Before
    fun setUp() {
        contentResolver = mock(ContentResolver::class.java)
        assetManager = mock(AssetManager::class.java)
    }

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun fakeUri(): Uri = mock(Uri::class.java)

    private fun startServer(
        entries: List<VideoEntry>,
        isFolderMode: Boolean,
        libraryName: String = "Library",
        defaultSort: String = "name"
    ): MediaHttpServer {
        val httpServer = MediaHttpServer(0, contentResolver, assetManager, entries, libraryName, isFolderMode, defaultSort)
        httpServer.start(5000, false)
        server = httpServer
        return httpServer
    }

    /** GETs [path] from [server] and returns (status code, response body). */
    private fun get(server: MediaHttpServer, path: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return code to body
    }

    @Test
    fun `video names containing HTML are escaped rather than injected as markup`() {
        val entries = listOf(
            VideoEntry(id = 1, name = "<script>alert(1)</script>.mp4", folderPath = "", uri = fakeUri())
        )
        val httpServer = startServer(entries, isFolderMode = true)

        val (code, body) = get(httpServer, "/browse")

        assertEquals(200, code)
        assertFalse("raw <script> tag must not appear unescaped", body.contains("<script>alert"))
        assertTrue("escaped name should be present instead", body.contains("&lt;script&gt;"))
    }

    @Test
    fun `sort by size orders videos largest first`() {
        val entries = listOf(
            VideoEntry(id = 1, name = "small.mp4", folderPath = "", uri = fakeUri(), sizeBytes = 100),
            VideoEntry(id = 2, name = "large.mp4", folderPath = "", uri = fakeUri(), sizeBytes = 900),
            VideoEntry(id = 3, name = "medium.mp4", folderPath = "", uri = fakeUri(), sizeBytes = 500)
        )
        val httpServer = startServer(entries, isFolderMode = true)

        val (code, body) = get(httpServer, "/browse?sort=size")

        assertEquals(200, code)
        val largeIndex = body.indexOf("large.mp4")
        val mediumIndex = body.indexOf("medium.mp4")
        val smallIndex = body.indexOf("small.mp4")
        assertTrue("large should be listed before medium", largeIndex in 0 until mediumIndex)
        assertTrue("medium should be listed before small", mediumIndex in 0 until smallIndex)
    }

    @Test
    fun `folder navigation shows subfolders at root and their contents one level in`() {
        val entries = listOf(
            VideoEntry(id = 1, name = "root.mp4", folderPath = "", uri = fakeUri()),
            VideoEntry(id = 2, name = "nested.mp4", folderPath = "Movies", uri = fakeUri())
        )
        val httpServer = startServer(entries, isFolderMode = true)

        val (rootCode, rootBody) = get(httpServer, "/browse")
        assertEquals(200, rootCode)
        assertTrue(rootBody.contains("root.mp4"))
        assertTrue(rootBody.contains("Movies"))
        assertFalse("nested video shouldn't show until its folder is opened", rootBody.contains("nested.mp4"))

        val (folderCode, folderBody) = get(httpServer, "/browse?path=Movies")
        assertEquals(200, folderCode)
        assertTrue(folderBody.contains("nested.mp4"))
    }

    @Test
    fun `single file mode serves the player directly at root`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = false)

        val (code, body) = get(httpServer, "/")

        assertEquals(200, code)
        assertTrue(body.contains("only.mp4"))
        assertTrue("player should use the video.js player", body.contains("video-js"))
    }

    @Test
    fun `watch with an unknown id returns 404`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = false)

        val (code, _) = get(httpServer, "/watch?id=999")

        assertEquals(404, code)
    }

    @Test
    fun `video route fails gracefully when the file can't be opened`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = false)

        // contentResolver is an unstubbed mock, so openFileDescriptor() returns null here —
        // this is exactly the "file went away / unreadable" case the server has to handle
        // without crashing.
        val (code, body) = get(httpServer, "/video?id=1")

        assertEquals(500, code)
        assertTrue(body.contains("Cannot open video file"))
    }

    private fun post(server: MediaHttpServer, path: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return code to body
    }

    @Test
    fun `remote state starts out unselected`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)

        val (code, body) = get(httpServer, "/remote/state")

        assertEquals(200, code)
        assertTrue(body.contains("\"videoId\":null"))
    }

    @Test
    fun `selecting a video via remote updates the polled state`() {
        val entries = listOf(
            VideoEntry(id = 1, name = "one.mp4", folderPath = "", uri = fakeUri()),
            VideoEntry(id = 2, name = "two.mp4", folderPath = "", uri = fakeUri())
        )
        val httpServer = startServer(entries, isFolderMode = true)

        val (selectCode, _) = post(httpServer, "/remote/select?id=2")
        assertEquals(200, selectCode)

        val (code, body) = get(httpServer, "/remote/state")
        assertEquals(200, code)
        assertTrue(body.contains("\"videoId\":2"))
    }

    @Test
    fun `selecting an unknown video via remote returns 404 and leaves state untouched`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)

        val (selectCode, _) = post(httpServer, "/remote/select?id=999")
        assertEquals(404, selectCode)

        val (_, body) = get(httpServer, "/remote/state")
        assertTrue(body.contains("\"videoId\":null"))
    }

    @Test
    fun `remote page lists videos as selectable cards rather than player links`() {
        val entries = listOf(
            VideoEntry(id = 1, name = "<b>weird</b>.mp4", folderPath = "", uri = fakeUri())
        )
        val httpServer = startServer(entries, isFolderMode = true)

        val (code, body) = get(httpServer, "/remote")

        assertEquals(200, code)
        assertTrue(body.contains("data-id=\"1\""))
        assertFalse("remote cards shouldn't link straight into the player", body.contains("/watch?id=1"))
        assertFalse("raw markup must not appear unescaped", body.contains("<b>weird</b>.mp4"))
    }

    @Test
    fun `remote is unavailable in single file mode`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = false)

        val (code, _) = get(httpServer, "/remote")

        assertEquals(404, code)
    }
}
