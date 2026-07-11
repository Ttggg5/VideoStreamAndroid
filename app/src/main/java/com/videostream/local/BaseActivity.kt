package com.videostream.local

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Applies the user's chosen accent color (Settings > Accent color) before each subclass's own
 * `onCreate` inflates its layout, and recreates the activity if the color changed while it was
 * paused (e.g. the user picked a new one in Settings and pressed back) — [AppCompatDelegate]
 * already recreates activities for a changed light/dark mode on its own, but a custom
 * [android.content.res.Resources.Theme] overlay like this one needs that handled manually.
 */
abstract class BaseActivity : AppCompatActivity() {

    private var appliedAccentColor: AppSettings.AccentColor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.applyAccentOverlay(this)
        appliedAccentColor = AppSettings.getAccentColor(this)
    }

    override fun onResume() {
        super.onResume()
        if (appliedAccentColor != null && appliedAccentColor != AppSettings.getAccentColor(this)) {
            recreate()
        }
    }
}
