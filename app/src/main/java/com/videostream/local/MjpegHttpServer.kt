package com.videostream.local

import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Minimal HTTP server that serves the latest camera frame as an MJPEG
 * (multipart/x-mixed-replace) stream, viewable by any browser on the LAN,
 * plus a tiny HTML landing page.
 */
class MjpegHttpServer(port: Int) : NanoHTTPD(port) {

    private val frameLock = Object()
    private var latestJpeg: ByteArray? = null

    /** Called from the camera analyzer thread whenever a new JPEG frame is ready. */
    fun updateFrame(jpeg: ByteArray) {
        synchronized(frameLock) {
            latestJpeg = jpeg
            frameLock.notifyAll()
        }
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/", "/index.html" -> newFixedLengthResponse(Response.Status.OK, "text/html", INDEX_HTML)
            "/stream" -> serveMjpegStream()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun serveMjpegStream(): Response {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, PIPE_BUFFER_SIZE)

        val worker = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val frame = synchronized(frameLock) {
                        var waited = 0
                        while (latestJpeg == null && waited < FRAME_WAIT_TIMEOUT_MS) {
                            frameLock.wait(200)
                            waited += 200
                        }
                        latestJpeg
                    } ?: continue

                    val header = "--$BOUNDARY\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${frame.size}\r\n\r\n"
                    pipedOut.write(header.toByteArray(Charsets.US_ASCII))
                    pipedOut.write(frame)
                    pipedOut.write("\r\n".toByteArray(Charsets.US_ASCII))
                    pipedOut.flush()

                    Thread.sleep(FRAME_INTERVAL_MS)
                }
            } catch (_: IOException) {
                // Client disconnected; nothing to do.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                try {
                    pipedOut.close()
                } catch (_: IOException) {
                }
            }
        }, "mjpeg-stream-worker")
        worker.isDaemon = true
        worker.start()

        val response = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$BOUNDARY",
            pipedIn
        )
        response.addHeader("Cache-Control", "no-cache, private")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Connection", "close")
        return response
    }

    companion object {
        private const val BOUNDARY = "videostreamlocalboundary"
        private const val PIPE_BUFFER_SIZE = 256 * 1024
        private const val FRAME_INTERVAL_MS = 60L // ~15-16 fps cap
        private const val FRAME_WAIT_TIMEOUT_MS = 2000

        private val INDEX_HTML = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Local Video Stream</title>
              <style>
                body { margin: 0; background: #111; display: flex; align-items: center; justify-content: center; height: 100vh; }
                img { max-width: 100%; max-height: 100%; }
              </style>
            </head>
            <body>
              <img src="/stream" alt="Live stream">
            </body>
            </html>
        """.trimIndent()
    }
}
