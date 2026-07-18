package com.videostream.local

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/**
 * Small SharedPreferences-backed store for the handful of user-facing app settings
 * (accent color, light/dark mode, default streaming port, keep-screen-on-while-watching).
 * Kept as one object rather than spread across each screen so every Activity applies the
 * same accent/theme consistently, and so [MediaHttpServer]/[StreamingService] read the same
 * port a user picked in Settings.
 */
object AppSettings {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_ACCENT = "accent_color"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_HTTP_PORT = "http_port"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on_watching"
    private const val KEY_BATTERY_OPT_PROMPT_DISMISSED = "battery_opt_prompt_dismissed"
    private const val KEY_SKIP_SECONDS = "skip_seconds"

    const val DEFAULT_HTTP_PORT = 8080
    const val MIN_HTTP_PORT = 1024
    const val MAX_HTTP_PORT = 65535

    const val DEFAULT_SKIP_SECONDS = 10
    const val MIN_SKIP_SECONDS = 1
    const val MAX_SKIP_SECONDS = 300

    enum class AccentColor(val id: String, val hex: String, val themeOverlayRes: Int, val labelRes: Int) {
        INDIGO("indigo", "#4A5FFF", R.style.ThemeOverlay_Accent_Indigo, R.string.accent_indigo),
        TEAL("teal", "#00BFA5", R.style.ThemeOverlay_Accent_Teal, R.string.accent_teal),
        PURPLE("purple", "#9C27B0", R.style.ThemeOverlay_Accent_Purple, R.string.accent_purple),
        GREEN("green", "#43A047", R.style.ThemeOverlay_Accent_Green, R.string.accent_green),
        ORANGE("orange", "#FB8C00", R.style.ThemeOverlay_Accent_Orange, R.string.accent_orange),
        ROSE("rose", "#E91E63", R.style.ThemeOverlay_Accent_Rose, R.string.accent_rose);

        companion object {
            fun fromId(id: String?): AccentColor = entries.firstOrNull { it.id == id } ?: INDIGO
        }
    }

    enum class ThemeMode(val id: String, val nightMode: Int, val labelRes: Int) {
        SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, R.string.theme_system),
        LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO, R.string.theme_light),
        DARK("dark", AppCompatDelegate.MODE_NIGHT_YES, R.string.theme_dark);

        companion object {
            fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAccentColor(context: Context): AccentColor =
        AccentColor.fromId(prefs(context).getString(KEY_ACCENT, null))

    fun setAccentColor(context: Context, color: AccentColor) {
        prefs(context).edit().putString(KEY_ACCENT, color.id).apply()
    }

    /** Applies the saved accent color as a theme overlay; call before setContentView(). */
    fun applyAccentOverlay(activity: Activity) {
        activity.theme.applyStyle(getAccentColor(activity).themeOverlayRes, true)
    }

    fun getThemeMode(context: Context): ThemeMode =
        ThemeMode.fromId(prefs(context).getString(KEY_THEME_MODE, null))

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode.id).apply()
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    /** Applies the saved light/dark mode; safe to call on every process start. */
    fun applyThemeMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(getThemeMode(context).nightMode)
    }

    fun getHttpPort(context: Context): Int =
        prefs(context).getInt(KEY_HTTP_PORT, DEFAULT_HTTP_PORT)

    fun setHttpPort(context: Context, port: Int) {
        val clamped = port.coerceIn(MIN_HTTP_PORT, MAX_HTTP_PORT)
        prefs(context).edit().putInt(KEY_HTTP_PORT, clamped).apply()
    }

    /** Whether WatchActivity should keep the screen on while a stream is loaded. */
    fun getKeepScreenOnWhileWatching(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, true)

    fun setKeepScreenOnWhileWatching(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
    }

    /** Whether the user has already dismissed the "exempt from battery optimization" prompt once. */
    fun getBatteryOptimizationPromptDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BATTERY_OPT_PROMPT_DISMISSED, false)

    fun setBatteryOptimizationPromptDismissed(context: Context, dismissed: Boolean) {
        prefs(context).edit().putBoolean(KEY_BATTERY_OPT_PROMPT_DISMISSED, dismissed).apply()
    }

    /** How many seconds the skip-back/skip-forward controls jump by (native player and /remote). */
    fun getSkipSeconds(context: Context): Int =
        prefs(context).getInt(KEY_SKIP_SECONDS, DEFAULT_SKIP_SECONDS)

    fun setSkipSeconds(context: Context, seconds: Int) {
        val clamped = seconds.coerceIn(MIN_SKIP_SECONDS, MAX_SKIP_SECONDS)
        prefs(context).edit().putInt(KEY_SKIP_SECONDS, clamped).apply()
    }
}
