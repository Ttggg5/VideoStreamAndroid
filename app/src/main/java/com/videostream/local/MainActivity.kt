package com.videostream.local

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import com.videostream.local.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var service: StreamingService? = null
    private var isBound = false
    private var observersAttached = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as StreamingService.LocalBinder
            service = localBinder.getService()
            isBound = true
            attachObservers()
            service?.setPreviewSurfaceProvider(binding.previewView.surfaceProvider)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            isBound = false
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            startStreaming()
        } else {
            binding.statusText.text = getString(R.string.status_camera_permission_required)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggleButton.setOnClickListener {
            if (service?.isStreaming?.value == true) {
                stopStreaming()
            } else {
                ensurePermissionsThenStart()
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
            service?.setPreviewSurfaceProvider(null)
            unbindService(connection)
            isBound = false
        }
        super.onStop()
    }

    private fun attachObservers() {
        if (observersAttached) return
        observersAttached = true
        service?.isStreaming?.observe(this) { streaming ->
            binding.toggleButton.text = getString(
                if (streaming) R.string.stop_streaming else R.string.start_streaming
            )
            if (!streaming) {
                binding.statusText.text = getString(R.string.status_idle)
            }
        }
        service?.serverUrl?.observe(this) { url ->
            if (url != null) {
                binding.statusText.text = getString(R.string.status_streaming_at, url)
            }
        }
    }

    private fun ensurePermissionsThenStart() {
        val neededPermissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = neededPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startStreaming()
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun startStreaming() {
        binding.statusText.text = getString(R.string.status_starting)
        val intent = Intent(this, StreamingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        if (isBound) {
            service?.setPreviewSurfaceProvider(binding.previewView.surfaceProvider)
        }
    }

    private fun stopStreaming() {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        startService(intent)
    }
}
