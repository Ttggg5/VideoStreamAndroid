package com.videostream.local

import android.content.ContentResolver
import android.content.res.AssetManager
import android.net.Uri
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Exercises `/remote/ws` — the push channel that replaced `/remote/state` polling (see
 * [MediaHttpServer]'s class doc) — over a real loopback WebSocket connection using OkHttp's
 * client, the same library [RemoteStateSocket] uses in the app itself.
 */
class MediaHttpServerWebSocketTest {

    private lateinit var contentResolver: ContentResolver
    private lateinit var assetManager: AssetManager
    private var server: MediaHttpServer? = null
    private val client = OkHttpClient()
    private val openSockets = mutableListOf<WebSocket>()

    @Before
    fun setUp() {
        contentResolver = mock(ContentResolver::class.java)
        assetManager = mock(AssetManager::class.java)
    }

    @After
    fun tearDown() {
        openSockets.forEach { it.close(1000, null) }
        server?.stop()
    }

    private fun fakeUri(): Uri = mock(Uri::class.java)

    private fun startServer(entries: List<VideoEntry>): MediaHttpServer {
        val httpServer = MediaHttpServer(0, contentResolver, assetManager, entries, "Library", isFolderMode = true, defaultSortParam = "name")
        httpServer.start(5000, false)
        server = httpServer
        return httpServer
    }

    /** Opens `/remote/ws` against [server] and returns a queue every pushed text message lands in. */
    private fun connectWs(server: MediaHttpServer): LinkedBlockingQueue<String> {
        val messages = LinkedBlockingQueue<String>()
        val request = Request.Builder().url("ws://127.0.0.1:${server.listeningPort}/remote/ws").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                messages.put(text)
            }
        })
        openSockets.add(socket)
        return messages
    }

    private fun post(server: MediaHttpServer, path: String) {
        val connection = java.net.URL("http://127.0.0.1:${server.listeningPort}$path")
            .openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.responseCode
        connection.disconnect()
    }

    @Test
    fun `connecting pushes the current remote state immediately`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries)

        val messages = connectWs(httpServer)

        val first = messages.poll(5, TimeUnit.SECONDS)
        assertNotNull("expected the current state to be pushed right on connect", first)
        assertTrue(first!!.contains("\"videoId\":null"))
    }

    @Test
    fun `a remote select push arrives without any polling`() {
        val entries = listOf(
            VideoEntry(id = 1, name = "one.mp4", folderPath = "", uri = fakeUri()),
            VideoEntry(id = 2, name = "two.mp4", folderPath = "", uri = fakeUri())
        )
        val httpServer = startServer(entries)
        val messages = connectWs(httpServer)
        val initial = messages.poll(5, TimeUnit.SECONDS)
        assertNotNull(initial)

        post(httpServer, "/remote/select?id=2")

        val pushed = messages.poll(5, TimeUnit.SECONDS)
        assertNotNull("expected a push after /remote/select, not just the initial state", pushed)
        assertTrue(pushed!!.contains("\"videoId\":2"))
    }

    @Test
    fun `every connected socket is pushed a play command`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries)
        post(httpServer, "/remote/select?id=1")
        val a = connectWs(httpServer)
        val b = connectWs(httpServer)
        assertNotNull(a.poll(5, TimeUnit.SECONDS))
        assertNotNull(b.poll(5, TimeUnit.SECONDS))

        post(httpServer, "/remote/command?action=pause")

        val pushedA = a.poll(5, TimeUnit.SECONDS)
        val pushedB = b.poll(5, TimeUnit.SECONDS)
        assertNotNull(pushedA)
        assertNotNull(pushedB)
        assertTrue(pushedA!!.contains("\"playing\":false"))
        assertTrue(pushedB!!.contains("\"playing\":false"))
    }
}
