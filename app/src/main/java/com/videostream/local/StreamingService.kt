package com.videostream.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that owns the camera (via CameraX) and an embedded HTTP
 * server, so the live camera feed keeps streaming to the local network even
 * while the app is backgrounded. [MainActivity] binds to this service to
 * show a local preview and to start/stop streaming.
 */
class StreamingService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    private val binder = LocalBinder()

    val isStreaming = MutableLiveData(false)
    val serverUrl = MutableLiveData<String?>(null)

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var mjpegServer: MjpegHttpServer? = null
    private var cameraExecutor: ExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopStreaming()
            else -> startStreaming()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    fun setPreviewSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        preview?.setSurfaceProvider(surfaceProvider)
    }

    private fun startStreaming() {
        if (isStreaming.value == true) return

        acquireWakeLock()

        val notification = buildNotification(getString(R.string.notification_starting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        val server = MjpegHttpServer(HTTP_PORT)
        try {
            server.start(NANOHTTPD_TIMEOUT_MS, false)
            mjpegServer = server
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
            stopStreaming()
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val previewUseCase = Preview.Builder().build()
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                    processFrame(imageProxy)
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase,
                    imageAnalysis
                )
                preview = previewUseCase

                val ip = NetworkUtils.getLocalIpAddress()
                val url = if (ip != null) "http://$ip:$HTTP_PORT" else null
                serverUrl.postValue(url)
                isStreaming.postValue(true)
                updateNotification(
                    if (url != null) getString(R.string.notification_streaming, url)
                    else getString(R.string.notification_no_network)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                stopStreaming()
            }
        }, mainExecutorCompat())
    }

    private fun stopStreaming() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        preview = null

        cameraExecutor?.shutdown()
        cameraExecutor = null

        mjpegServer?.stop()
        mjpegServer = null

        releaseWakeLock()

        isStreaming.postValue(false)
        serverUrl.postValue(null)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val nv21 = yuv420888ToNv21(imageProxy)
            val jpeg = nv21ToJpeg(
                nv21,
                imageProxy.width,
                imageProxy.height,
                imageProxy.imageInfo.rotationDegrees,
                JPEG_QUALITY
            )
            mjpegServer?.updateFrame(jpeg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process frame", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + width * height / 2)
        var pos = 0

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
            pos = ySize
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * vRowStride + col * vPixelStride
                val uIndex = row * uRowStride + col * uPixelStride
                nv21[pos++] = vBuffer.get(vIndex)
                nv21[pos++] = uBuffer.get(uIndex)
            }
        }
        return nv21
    }

    private fun nv21ToJpeg(
        nv21: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        quality: Int
    ): ByteArray {
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, out)
        val jpegBytes = out.toByteArray()
        if (rotationDegrees == 0) return jpegBytes

        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val rotatedOut = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, quality, rotatedOut)
        bitmap.recycle()
        rotated.recycle()
        return rotatedOut.toByteArray()
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
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_stop_action), stopPendingIntent)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        cameraExecutor?.shutdown()
        mjpegServer?.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun mainExecutorCompat() = androidx.core.content.ContextCompat.getMainExecutor(this)

    companion object {
        private const val TAG = "StreamingService"
        const val ACTION_STOP = "com.videostream.local.action.STOP"
        const val HTTP_PORT = 8080
        private const val CHANNEL_ID = "streaming_channel"
        private const val NOTIFICATION_ID = 1
        private const val JPEG_QUALITY = 70
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L // 12h safety cap
        private const val NANOHTTPD_TIMEOUT_MS = 5000
    }
}
