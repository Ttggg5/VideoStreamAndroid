package com.videostream.local

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.videostream.local.databinding.ActivitySettingsBinding

/**
 * Lets the user pick a gradient theme, the default streaming port, the skip interval, and
 * playback preferences. [BaseActivity] re-themes every other open activity automatically once
 * the theme changes here. (The app is always dark, so there's no light/dark choice.)
 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        renderAccentSwatches()

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

    /** One gradient circle + name per [AppSettings.AccentColor]; tapping one re-themes. */
    private fun renderAccentSwatches() {
        binding.accentColorRow.removeAllViews()
        val selected = AppSettings.getAccentColor(this)
        val swatchSize = resources.getDimensionPixelSize(R.dimen.accent_swatch_size)
        val swatchMargin = resources.getDimensionPixelSize(R.dimen.accent_swatch_margin)
        val density = resources.displayMetrics.density

        for (color in AppSettings.AccentColor.entries) {
            val isSelected = color == selected
            val circle = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(swatchSize, swatchSize)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.parseColor(color.startHex), Color.parseColor(color.endHex))
                ).apply {
                    shape = GradientDrawable.OVAL
                    if (isSelected) setStroke((3 * density).toInt(), Color.WHITE)
                }
            }
            if (isSelected) {
                circle.addView(ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(Color.WHITE)
                })
            }
            val label = TextView(this).apply {
                text = getString(color.labelRes)
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(
                    if (isSelected) Color.WHITE
                    else MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
                )
                setPadding(0, (6 * density).toInt(), 0, 0)
            }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = swatchMargin }
                isClickable = true
                isFocusable = true
                contentDescription = getString(R.string.accent_color_description, getString(color.labelRes))
                setOnClickListener {
                    if (!isSelected) {
                        AppSettings.setAccentColor(this@SettingsActivity, color)
                        recreate()
                    }
                }
                addView(circle)
                addView(label)
            }
            binding.accentColorRow.addView(item)
        }
    }
}
