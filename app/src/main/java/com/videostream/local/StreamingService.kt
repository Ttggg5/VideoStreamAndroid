package com.videostream.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.MutableLiveData

/**
 * Foreground service that owns the embedded HTTP server, so serving a chosen
 * video file or folder of videos to the local network keeps going even while
 * the app is backgrounded. [HostActivity] binds to this service to observe
 * status and to start/stop streaming.
 */
class StreamingService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    private val binder = LocalBinder()

    val isStreaming = MutableLiveData(false)
    val serverUrl = MutableLiveData<String?>(null)
    val videoName = MutableLiveData<String?>(null)

    private var server: MediaHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopStreaming()
            ACTION_START -> handleStart(intent)
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun handleStart(intent: Intent) {
        val name = intent.getStringExtra(EXTRA_VIDEO_NAME) ?: "video"
        val fileUriString = intent.getStringExtra(EXTRA_VIDEO_URI)
        val folderUriString = intent.getStringExtra(EXTRA_FOLDER_URI)
        when {
            fileUriString != null -> {
                val entry = VideoEntry(0, name, folderPath = "", uri = Uri.parse(fileUriString))
                startStreaming(listOf(entry), name, isFolderMode = false)
            }
            folderUriString != null -> {
                val entries = scanFolderForVideos(Uri.parse(folderUriString))
                val label = getString(R.string.library_summary, name, entries.size)
                startStreaming(entries, label, isFolderMode = true)
            }
        }
    }

    private fun scanFolderForVideos(treeUri: Uri): List<VideoEntry> {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: return emptyList()
        val results = mutableListOf<VideoEntry>()
        scanDir(root, "", results, 0)
        return results
    }

    /** [folderPath] is this directory's path relative to the chosen root ("" for the root itself). */
    private fun scanDir(dir: DocumentFile, folderPath: String, results: MutableList<VideoEntry>, depth: Int) {
        if (results.size >= MAX_LIBRARY_ENTRIES || depth > MAX_SCAN_DEPTH) return
        for (child in dir.listFiles()) {
            if (results.size >= MAX_LIBRARY_ENTRIES) break
            val childName = child.name ?: continue
            if (child.isDirectory) {
                val childPath = if (folderPath.isEmpty()) childName else "$folderPath/$childName"
                scanDir(child, childPath, results, depth + 1)
            } else if (isVideoFile(child)) {
                results.add(
                    VideoEntry(
                        id = results.size,
                        name = childName,
                        folderPath = folderPath,
                        uri = child.uri,
                        lastModified = child.lastModified(),
                        sizeBytes = child.length()
                    )
                )
            }
        }
    }

    private fun isVideoFile(file: DocumentFile): Boolean {
        val type = file.type
        if (type != null && type.startsWith("video/")) return true
        val name = file.name ?: return false
        return VIDEO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
    }

    private fun startStreaming(entries: List<VideoEntry>, libraryLabel: String, isFolderMode: Boolean) {
        if (isStreaming.value == true) return

        // startForeground() must be called promptly whenever the service was launched via
        // startForegroundService() (as HostActivity always does), or Android kills the app with
        // ForegroundServiceDidNotStartInTimeException — so this runs before the entries.isEmpty()
        // check below, and that error path tears back down through stopStreaming() instead of
        // returning early.
        acquireWakeLock()
        val notification = buildNotification(getString(R.string.notification_starting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (entries.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_no_videos_found), Toast.LENGTH_LONG).show()
            stopStreaming()
            return
        }

        val httpServer = MediaHttpServer(HTTP_PORT, contentResolver, entries, libraryLabel, isFolderMode)
        try {
            httpServer.start(NANOHTTPD_TIMEOUT_MS, false)
            server = httpServer
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
            stopStreaming()
            return
        }

        videoName.postValue(libraryLabel)
        val ip = NetworkUtils.getLocalIpAddress()
        val url = if (ip != null) "http://$ip:$HTTP_PORT" else null
        serverUrl.postValue(url)
        isStreaming.postValue(true)
        updateNotification(
            if (url != null) getString(R.string.notification_streaming, url)
            else getString(R.string.notification_no_network)
        )
    }

    private fun stopStreaming() {
        server?.stop()
        server = null

        releaseWakeLock()

        isStreaming.postValue(false)
        serverUrl.postValue(null)
        videoName.postValue(null)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$TAG:streamingWakeLock"
        ).apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val stopIntent = Intent(this, StreamingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_stop_action), stopPendingIntent)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    override fun onDestroy() {
        server?.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StreamingService"
        const val ACTION_START = "com.videostream.local.action.START"
        const val ACTION_STOP = "com.videostream.local.action.STOP"
        const val EXTRA_VIDEO_URI = "com.videostream.local.extra.VIDEO_URI"
        const val EXTRA_FOLDER_URI = "com.videostream.local.extra.FOLDER_URI"
        const val EXTRA_VIDEO_NAME = "com.videostream.local.extra.VIDEO_NAME"
        const val HTTP_PORT = 8080
        private const val CHANNEL_ID = "streaming_channel"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L // 12h safety cap
        private const val NANOHTTPD_TIMEOUT_MS = 5000
        private const val MAX_LIBRARY_ENTRIES = 500
        private const val MAX_SCAN_DEPTH = 6
        private val VIDEO_EXTENSIONS = listOf(
            ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".3gp", ".ts", ".flv", ".wmv", ".mpg", ".mpeg"
        )
    }
}
