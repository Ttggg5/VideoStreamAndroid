package com.videostream.local

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.documentfile.provider.DocumentFile
import com.videostream.local.databinding.ActivityHostBinding

/** Lets the user pick a video file or a folder of videos and broadcast it to the local network. */
class HostActivity : BaseActivity() {

    private lateinit var binding: ActivityHostBinding

    private var service: StreamingService? = null
    private var isBound = false
    private var observedService: StreamingService? = null

    private var selectedUri: Uri? = null
    private var selectedName: String? = null
    private var selectedIsFolder: Boolean = false

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

    private val pickVideoFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFileSelected(uri)
    }

    private val pickVideoFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onFolderSelected(uri)
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startStreaming() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.defaultSortSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf(getString(R.string.sort_name), getString(R.string.sort_date), getString(R.string.sort_size))
        )

        binding.chooseFileButton.setOnClickListener {
            pickVideoFile.launch(arrayOf("video/*"))
        }
        binding.chooseFolderButton.setOnClickListener {
            pickVideoFolder.launch(null)
        }

        binding.toggleButton.setOnClickListener {
            if (service?.isStreaming?.value == true) {
                stopStreaming()
            } else {
                ensureNotificationPermissionThenStart()
            }
        }
        binding.remoteControlButton.setOnClickListener {
            startActivity(Intent(this, RemoteControlActivity::class.java))
        }

        // Picking a file/folder is stored in plain fields, not view state, so it survives
        // a rotation (or any other config-change recreation, e.g. entering landscape) on its
        // own — otherwise the picked video would silently disappear from under the user.
        if (savedInstanceState != null) {
            selectedUri = BundleCompat.getParcelable(savedInstanceState, STATE_SELECTED_URI, Uri::class.java)
            selectedName = savedInstanceState.getString(STATE_SELECTED_NAME)
            selectedIsFolder = savedInstanceState.getBoolean(STATE_SELECTED_IS_FOLDER)
            if (selectedUri != null) {
                applySelectionToUi()
                if (!selectedIsFolder) loadThumbnailPreview(selectedUri!!)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(STATE_SELECTED_URI, selectedUri)
        outState.putString(STATE_SELECTED_NAME, selectedName)
        outState.putBoolean(STATE_SELECTED_IS_FOLDER, selectedIsFolder)
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

    private fun onFileSelected(uri: Uri) {
        takePersistablePermission(uri)
        selectedUri = uri
        selectedIsFolder = false
        selectedName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "video"
        applySelectionToUi()
        loadThumbnailPreview(uri)
    }

    private fun onFolderSelected(uri: Uri) {
        takePersistablePermission(uri)
        selectedUri = uri
        selectedIsFolder = true
        selectedName = DocumentFile.fromTreeUri(this, uri)?.name ?: "Folder"
        applySelectionToUi()
        hideThumbnailPreview()
    }

    /** Reflects [selectedName]/[selectedIsFolder] in the UI, whether freshly picked or restored. */
    private fun applySelectionToUi() {
        binding.selectedFileText.text = if (selectedIsFolder) {
            getString(R.string.selected_folder, selectedName)
        } else {
            getString(R.string.selected_video, selectedName)
        }
        binding.toggleButton.isEnabled = true
        setFolderOptionsVisible(selectedIsFolder)
    }

    /** The default-sort picker only makes sense once there's a folder of videos to sort. */
    private fun setFolderOptionsVisible(visible: Boolean) {
        binding.sortOptionRow.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Folders don't have a single representative thumbnail, so this only applies to single-file mode. */
    private fun loadThumbnailPreview(uri: Uri) {
        hideThumbnailPreview()
        Thread {
            val jpeg = ThumbnailUtil.extractThumbnailJpeg(contentResolver, uri)
            val bitmap = jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            runOnUiThread {
                // Only apply if the user hasn't picked something else while this was loading.
                if (bitmap != null && selectedUri == uri) {
                    binding.thumbnailPreview.setImageBitmap(bitmap)
                    binding.thumbnailPreview.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun hideThumbnailPreview() {
        binding.thumbnailPreview.visibility = View.GONE
        binding.thumbnailPreview.setImageDrawable(null)
    }

    private fun takePersistablePermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers don't support persistable grants; the current-session grant still works.
        }
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
            binding.toggleButton.setIconResource(if (streaming) R.drawable.ic_stop else R.drawable.ic_play_arrow)
            binding.toggleButton.isEnabled = streaming || selectedUri != null
            binding.chooseFileButton.isEnabled = !streaming
            binding.chooseFolderButton.isEnabled = !streaming
            // Only folder mode has more than one video for a remote to choose between.
            binding.remoteControlButton.visibility =
                if (streaming && selectedIsFolder) View.VISIBLE else View.GONE
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
                binding.selectedFileText.text = name
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
            putExtra(StreamingService.EXTRA_VIDEO_NAME, selectedName)
            if (selectedIsFolder) {
                putExtra(StreamingService.EXTRA_FOLDER_URI, uri.toString())
                putExtra(
                    StreamingService.EXTRA_DEFAULT_SORT,
                    SORT_VALUES.getOrElse(binding.defaultSortSpinner.selectedItemPosition) {
                        StreamingService.DEFAULT_SORT_PARAM
                    }
                )
            } else {
                putExtra(StreamingService.EXTRA_VIDEO_URI, uri.toString())
            }
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopStreaming() {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        startService(intent)
    }

    companion object {
        // Must stay in the same order as the labels populating defaultSortSpinner's adapter.
        private val SORT_VALUES = arrayOf("name", "date", "size")

        private const val STATE_SELECTED_URI = "selectedUri"
        private const val STATE_SELECTED_NAME = "selectedName"
        private const val STATE_SELECTED_IS_FOLDER = "selectedIsFolder"
    }
}
