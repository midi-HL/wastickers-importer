package io.github.midihl.wastickersimporter

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {
    private lateinit var appliedPalette: ThemeSettings.Palette
    private var refreshingPalette = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // AppCompat has applied night mode. Resolve colors before inflating the screen.
        appliedPalette = ThemeSettings.palette(this)
        val overlay = appliedPalette.overlay
        theme.applyStyle(overlay, true)
        window.peekDecorView()?.context?.theme?.takeIf { it !== theme }?.applyStyle(overlay, true)
        val surface = com.google.android.material.color.MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurface, android.graphics.Color.TRANSPARENT
        )
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(surface))
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val light = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    protected fun refreshPaletteIfNeeded(): Boolean {
        if (refreshingPalette) return true
        if (isFinishing || isDestroyed || appliedPalette == ThemeSettings.palette(this)) return false
        refreshingPalette = true
        recreate()
        return true
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (UiMotion.animationsEnabled(this)) {
            overridePendingTransition(R.anim.m3_fade_hold, R.anim.m3_fade_hold)
        }
    }
}
