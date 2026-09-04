package com.videostream.local

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.videostream.local.databinding.ActivitySettingsBinding

/**
 * Lets the user customize the app's accent color, light/dark mode, default streaming port,
 * and whether the screen stays on while watching. [BaseActivity] re-themes every other open
 * activity automatically once the accent color changes here; light/dark mode changes are
 * picked up by [androidx.appcompat.app.AppCompatDelegate] on its own.
 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        renderAccentSwatches()

        binding.themeRadioGroup.check(
            when (AppSettings.getThemeMode(this)) {
                AppSettings.ThemeMode.SYSTEM -> R.id.themeSystemRadio
                AppSettings.ThemeMode.LIGHT -> R.id.themeLightRadio
                AppSettings.ThemeMode.DARK -> R.id.themeDarkRadio
            }
        )
        binding.themeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.themeLightRadio -> AppSettings.ThemeMode.LIGHT
                R.id.themeDarkRadio -> AppSettings.ThemeMode.DARK
                else -> AppSettings.ThemeMode.SYSTEM
            }
            AppSettings.setThemeMode(this, mode)
        }

        binding.portInput.setText(AppSettings.getHttpPort(this).toString())
        binding.portInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitPort()
        }

        binding.keepScreenOnSwitch.isChecked = AppSettings.getKeepScreenOnWhileWatching(this)
        binding.keepScreenOnSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setKeepScreenOnWhileWatching(this, isChecked)
        }

        binding.skipSecondsInput.setText(AppSettings.getSkipSeconds(this).toString())
        binding.skipSecondsInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitSkipSeconds()
        }

        binding.vibrateOnControlSwitch.isChecked = AppSettings.getVibrateOnControl(this)
        binding.vibrateOnControlSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setVibrateOnControl(this, isChecked)
        }
    }

    override fun onPause() {
        commitPort()
        commitSkipSeconds()
        super.onPause()
    }

    /** Clamps/saves whatever's in the port field; called on blur and when leaving the screen. */
    private fun commitPort() {
        val text = binding.portInput.text?.toString().orEmpty()
        val typed = text.toIntOrNull() ?: return
        AppSettings.setHttpPort(this, typed)
        val saved = AppSettings.getHttpPort(this)
        if (saved.toString() != text) {
            binding.portInput.setText(saved.toString())
        }
    }

    /** Clamps/saves whatever's in the skip-interval field; called on blur and when leaving. */
    private fun commitSkipSeconds() {
        val text = binding.skipSecondsInput.text?.toString().orEmpty()
        val typed = text.toIntOrNull() ?: return
        AppSettings.setSkipSeconds(this, typed)
        val saved = AppSettings.getSkipSeconds(this)
        if (saved.toString() != text) {
            binding.skipSecondsInput.setText(saved.toString())
        }
    }

    private fun renderAccentSwatches() {
        binding.accentColorRow.removeAllViews()
        val selected = AppSettings.getAccentColor(this)
        val swatchSize = resources.getDimensionPixelSize(R.dimen.accent_swatch_size)
        val swatchMargin = resources.getDimensionPixelSize(R.dimen.accent_swatch_margin)

        for (color in AppSettings.AccentColor.entries) {
            val swatch = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(swatchSize, swatchSize).apply {
                    marginEnd = swatchMargin
                }
                background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_color_swatch)
                    ?.mutate()
                    ?.also { (it as GradientDrawable).setColor(Color.parseColor(color.hex)) }
                isClickable = true
                isFocusable = true
                contentDescription = getString(R.string.accent_color_description, getString(color.labelRes))
                setOnClickListener {
                    AppSettings.setAccentColor(this@SettingsActivity, color)
                    renderAccentSwatches()
                    recreate()
                }
            }
            if (color == selected) {
                val check = ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(Color.WHITE)
                }
                swatch.addView(check)
            }
            binding.accentColorRow.addView(swatch)
        }
    }
}
