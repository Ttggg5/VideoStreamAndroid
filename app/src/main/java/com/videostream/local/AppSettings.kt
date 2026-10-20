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
    private const val KEY_VIBRATE_ON_CONTROL = "vibrate_on_control"

    const val DEFAULT_HTTP_PORT = 8080
    const val MIN_HTTP_PORT = 1024
    const val MAX_HTTP_PORT = 65535

    const val DEFAULT_SKIP_SECONDS = 10
    const val MIN_SKIP_SECONDS = 1
    const val MAX_SKIP_SECONDS = 300

    /**
     * Gradient themes (Settings > Theme). Each recolors the whole app (via its ThemeOverlay,
     * which sets colorPrimary/colorSecondary to the two gradient stops) and the served web
     * pages (StreamingService passes both stops to MediaHttpServer). [hex] is the start stop,
     * kept for callers that only need a single representative color.
     */
    enum class AccentColor(
        val id: String,
        val startHex: String,
        val endHex: String,
        val themeOverlayRes: Int,
        val labelRes: Int
    ) {
        AURORA("aurora", "#7C4DFF", "#FF4FA3", R.style.ThemeOverlay_Accent_Aurora, R.string.accent_aurora),
        OCEAN("ocean", "#2F7BFF", "#00D2FF", R.style.ThemeOverlay_Accent_Ocean, R.string.accent_ocean),
        SUNSET("sunset", "#FF8A3D", "#FF3D7F", R.style.ThemeOverlay_Accent_Sunset, R.string.accent_sunset),
        EMERALD("emerald", "#00C9A7", "#7BE36A", R.style.ThemeOverlay_Accent_Emerald, R.string.accent_emerald),
        NEON("neon", "#00E5FF", "#B44DFF", R.style.ThemeOverlay_Accent_Neon, R.string.accent_neon),
        EMBER("ember", "#FF4D4D", "#FFB03B", R.style.ThemeOverlay_Accent_Ember, R.string.accent_ember);

        val hex: String get() = startHex

        companion object {
            // Unknown/legacy ids (e.g. the old flat "indigo") fall back to the default theme.
            fun fromId(id: String?): AccentColor = entries.firstOrNull { it.id == id } ?: AURORA
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

    /** The app is always dark (the cinematic design has no light variant), so this forces
     *  night mode regardless of any older saved light/dark preference. Safe on every start. */
    fun applyThemeMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
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

    /** Whether the /remote control panel buzzes (navigator.vibrate) when a control button
     *  is pressed. Read once when the stream starts and baked into the served page. */
    fun getVibrateOnControl(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VIBRATE_ON_CONTROL, true)

    fun setVibrateOnControl(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIBRATE_ON_CONTROL, enabled).apply()
    }
}
