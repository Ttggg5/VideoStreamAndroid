package com.videostream.local

import android.os.Handler
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Keeps a persistent WebSocket connection to a host's `/remote/ws`, invoking [onState] (always on
 * [mainHandler]'s thread) for the current state the instant it connects and again on every later
 * push — the native counterpart of the `/remote/ws` pages' own reconnect-on-close JS, and what
 * replaces [WatchActivity]'s old `/remote/state` polling loop.
 */
class RemoteStateSocket(
    private val baseUrl: String,
    private val mainHandler: Handler,
    private val onState: (RemoteState) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var stopped = false

    fun start() {
        stopped = false
        connect()
    }

    /** Idempotent — safe to call even if [start] was never called or already stopped. */
    fun stop() {
        stopped = true
        webSocket?.close(1000, null)
        webSocket = null
    }

    private fun connect() {
        if (stopped) return
        val wsUrl = baseUrl.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://") + "/remote/ws"
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val state = RemoteLibraryApi.parseRemoteState(text) ?: return
                mainHandler.post { onState(state) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped) return
        mainHandler.postDelayed({ connect() }, 1000)
    }
}
