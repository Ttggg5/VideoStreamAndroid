package com.videostream.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    /**
     * Whether the active stream is a folder (vs. a single file) — the authoritative source for
     * this, since [HostActivity] itself may be a fresh instance that never made the pick (e.g.
     * the user backed out and reopened Host while a previous instance's stream is still running)
     * and can't tell folder from single-file mode from its own local state alone.
     */
    val isFolderStream = MutableLiveData(false)
    /**
     * The originally-picked file/folder URI backing the active stream, so a fresh [HostActivity]
     * (reopened while a previous instance's stream is still running) can restore the file
     * thumbnail / folder preview it never picked itself. Null when not streaming.
     */
    val streamSourceUri = MutableLiveData<Uri?>(null)

    // Written from the background scan/start thread, read from stop/onDestroy on the main thread.
    @Volatile private var server: MediaHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    // True from the moment a start is accepted until it either finishes publishing state or is
    // torn down by a stop that arrived mid-scan. Guards against a second start racing the first,
    // and lets the background thread know a stop beat it to the finish line. Main-thread only.
    private var starting = false

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
        if (starting || isStreaming.value == true) return
        val name = intent.getStringExtra(EXTRA_VIDEO_NAME) ?: "video"
        val fileUriString = intent.getStringExtra(EXTRA_VIDEO_URI)
        val folderUriString = intent.getStringExtra(EXTRA_FOLDER_URI)
        val defaultSort = intent.getStringExtra(EXTRA_DEFAULT_SORT) ?: DEFAULT_SORT_PARAM
        if (fileUriString == null && folderUriString == null) return

        // Enter the foreground immediately. startForegroundService() requires startForeground()
        // within ~5s; the folder scan below is a SAF ContentResolver query per file and is now
        // unbounded, so it can easily outlast that deadline and must not run before this point.
        starting = true
        acquireWakeLock()
        acquireWifiLock()
        startInForeground(getString(R.string.notification_starting))

        // Scan + HTTP server startup run off the main thread so a large folder can't ANR the app
        // (or blow the foreground-start deadline). The lightweight "publish state" step is posted
        // back to the main thread in startStreaming().
        Thread {
            if (fileUriString != null) {
                val fileUri = Uri.parse(fileUriString)
                val entry = VideoEntry(0, name, folderPath = "", uri = fileUri)
                startStreaming(listOf(entry), name, isFolderMode = false, defaultSort = DEFAULT_SORT_PARAM, sourceUri = fileUri)
            } else {
                val folderUri = Uri.parse(folderUriString!!)
                val entries = scanFolderForVideos(folderUri)
                val label = getString(R.string.library_summary, name, entries.size)
                startStreaming(entries, label, isFolderMode = true, defaultSort = defaultSort, sourceUri = folderUri)
            }
        }.start()
    }

    private fun startInForeground(contentText: String) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun scanFolderForVideos(treeUri: Uri): List<VideoEntry> {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: return emptyList()
        val results = mutableListOf<VideoEntry>()
        scanDir(root, "", results, 0)
        return results
    }

    /** [folderPath] is this directory's path relative to the chosen root ("" for the root itself).
     *  There's no cap on the number of videos collected — a hosted folder includes every video in
     *  it. [depth] is bounded only as a guard against pathologically deep trees blowing the stack,
     *  not as a feature limit; realistic libraries never approach it. */
    private fun scanDir(dir: DocumentFile, folderPath: String, results: MutableList<VideoEntry>, depth: Int) {
        if (depth > MAX_SCAN_DEPTH) return
        for (child in dir.listFiles()) {
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

    /** Runs on the background thread started by [handleStart] — the foreground service is already
     *  up by now, so this only builds/starts the server and then publishes state on the main thread. */
    private fun startStreaming(
        entries: List<VideoEntry>,
        libraryLabel: String,
        isFolderMode: Boolean,
        defaultSort: String,
        sourceUri: Uri
    ) {
        if (entries.isEmpty()) {
            mainHandler.post {
                Toast.makeText(this, getString(R.string.error_no_videos_found), Toast.LENGTH_LONG).show()
                stopStreaming()
            }
            return
        }

        val port = AppSettings.getHttpPort(this)
        val accentHex = AppSettings.getAccentColor(this).hex
        val skipSeconds = AppSettings.getSkipSeconds(this)
        val httpServer = MediaHttpServer(
            port, contentResolver, assets, entries, libraryLabel, isFolderMode, defaultSort, accentHex, skipSeconds
        )
        try {
            httpServer.start(NANOHTTPD_TIMEOUT_MS, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
            mainHandler.post { stopStreaming() }
            return
        }
        // Assigned before the main-thread hop so a concurrent stopStreaming()/onDestroy() can
        // always find and shut down the just-started server.
        server = httpServer

        mainHandler.post {
            if (!starting) {
                // A stop arrived while we were scanning/starting — tear the just-started server
                // back down instead of publishing a stream nobody asked to keep.
                httpServer.stop()
                if (server === httpServer) server = null
                return@post
            }
            videoName.value = libraryLabel
            isFolderStream.value = isFolderMode
            streamSourceUri.value = sourceUri
            val ip = NetworkUtils.getLocalIpAddress()
            val url = if (ip != null) "http://$ip:$port" else null
            serverUrl.value = url
            isStreaming.value = true
            updateNotification(
                if (url != null) getString(R.string.notification_streaming, url)
                else getString(R.string.notification_no_network)
            )
            // Lets WatchActivity find this host automatically instead of requiring a typed-in
            // address; if registration fails for any reason (e.g. mDNS blocked on this network),
            // the URL above still works for manual connect.
            registerNsdService(libraryLabel, port)
            starting = false
        }
    }

    private fun stopStreaming() {
        // Signals an in-flight background start (if any) to abandon itself rather than publish.
        starting = false
        unregisterNsdService()

        server?.stop()
        server = null

        releaseWakeLock()
        releaseWifiLock()

        isStreaming.postValue(false)
        serverUrl.postValue(null)
        videoName.postValue(null)
        isFolderStream.postValue(false)
        streamSourceUri.postValue(null)

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

    /**
     * Without this, some devices drop Wi-Fi into a low-power/sleep state once the screen is
     * off, which stalls the HTTP server's open connections even though the CPU wake lock above
     * keeps the process itself alive.
     */
    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager ?: return
        val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(lockMode, "$TAG:streamingWifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    /** Advertises this stream via mDNS/NSD so [WatchActivity] can discover it without a typed address. */
    private fun registerNsdService(libraryLabel: String, httpPort: Int) {
        val manager = (getSystemService(NSD_SERVICE) as? NsdManager) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "${libraryLabel.take(30)} (${Build.MODEL})".take(60)
            serviceType = NSD_SERVICE_TYPE
            port = httpPort
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdManager = manager
            nsdRegistrationListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "Could not register NSD service", e)
        }
    }

    private fun unregisterNsdService() {
        val manager = nsdManager
        val listener = nsdRegistrationListener
        nsdManager = null
        nsdRegistrationListener = null
        if (manager != null && listener != null) {
            try {
                manager.unregisterService(listener)
            } catch (e: Exception) {
                // Already unregistered, or registration never succeeded; nothing to clean up.
            }
        }
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
        unregisterNsdService()
        server?.stop()
        releaseWakeLock()
        releaseWifiLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StreamingService"
        const val ACTION_START = "com.videostream.local.action.START"
        const val ACTION_STOP = "com.videostream.local.action.STOP"
        const val EXTRA_VIDEO_URI = "com.videostream.local.extra.VIDEO_URI"
        const val EXTRA_FOLDER_URI = "com.videostream.local.extra.FOLDER_URI"
        const val EXTRA_VIDEO_NAME = "com.videostream.local.extra.VIDEO_NAME"
        const val EXTRA_DEFAULT_SORT = "com.videostream.local.extra.DEFAULT_SORT"
        const val DEFAULT_SORT_PARAM = "name"
        /** NSD/mDNS service type this app's streams advertise themselves under, for WatchActivity to discover. */
        const val NSD_SERVICE_TYPE = "_videostream._tcp."
        private const val CHANNEL_ID = "streaming_channel"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L // 12h safety cap
        private const val NANOHTTPD_TIMEOUT_MS = 5000
        // Recursion-depth guard only (see scanDir) — deep enough that no real folder tree hits it,
        // just a backstop against a pathologically deep tree overflowing the stack.
        private const val MAX_SCAN_DEPTH = 100
        private val VIDEO_EXTENSIONS = listOf(
            ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".3gp", ".ts", ".flv", ".wmv", ".mpg", ".mpeg"
        )
    }
}
