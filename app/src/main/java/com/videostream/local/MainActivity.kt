package com.videostream.local

import android.content.Intent
import android.os.Bundle
import com.videostream.local.databinding.ActivityMainBinding

/** Landing screen: choose whether this device hosts a stream or watches one. */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.hostButton.setOnClickListener {
            startActivity(Intent(this, HostActivity::class.java))
        }
        binding.watchButton.setOnClickListener {
            startActivity(Intent(this, WatchActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
