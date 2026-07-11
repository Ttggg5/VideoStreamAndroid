package com.videostream.local

import android.app.Application

/** Applies the saved Settings > Theme choice before any activity is created, to avoid a flash of the wrong mode. */
class VideoStreamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.applyThemeMode(this)
    }
}
