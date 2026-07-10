package com.videostream.local

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.videostream.local.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var service: StreamingService? = null
    private var isBound = false
    private var observedService: StreamingService? = null

    private var selectedUri: Uri? = null
    private var selectedName: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as StreamingService.LocalBinder
            service = localBinder.getService()
            isBound = true
            attachObservers()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            isBound = false
        }
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onVideoSelected(uri)
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startStreaming() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.chooseFileButton.setOnClickListener {
            pickVideo.launch(arrayOf("video/*"))
        }

        binding.toggleButton.setOnClickListener {
            if (service?.isStreaming?.value == true) {
                stopStreaming()
            } else {
                ensureNotificationPermissionThenStart()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, StreamingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onStop()
    }

    private fun onVideoSelected(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers don't support persistable grants; the current-session grant still works.
        }
        selectedUri = uri
        selectedName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "video"
        binding.selectedFileText.text = getString(R.string.selected_video, selectedName)
        binding.toggleButton.isEnabled = true
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return null
    }

    private fun attachObservers() {
        val currentService = service ?: return
        if (observedService === currentService) return
        observedService = currentService

        currentService.isStreaming.observe(this) { streaming ->
            binding.toggleButton.text = getString(
                if (streaming) R.string.stop_streaming else R.string.start_streaming
            )
            binding.toggleButton.isEnabled = streaming || selectedUri != null
            binding.chooseFileButton.isEnabled = !streaming
            if (!streaming) {
                binding.statusText.text = getString(R.string.status_idle)
            }
        }
        currentService.serverUrl.observe(this) { url ->
            if (url != null) {
                binding.statusText.text = getString(R.string.status_streaming_at, url)
            }
        }
        currentService.videoName.observe(this) { name ->
            if (name != null && selectedUri == null) {
                binding.selectedFileText.text = getString(R.string.selected_video, name)
            }
        }
    }

    private fun ensureNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startStreaming()
        }
    }

    private fun startStreaming() {
        val uri = selectedUri ?: return
        binding.statusText.text = getString(R.string.status_starting)
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_VIDEO_URI, uri.toString())
            putExtra(StreamingService.EXTRA_VIDEO_NAME, selectedName)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopStreaming() {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        startService(intent)
    }
}
