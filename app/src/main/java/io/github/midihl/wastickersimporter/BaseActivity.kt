package io.github.midihl.wastickersimporter

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeSettings.applySeedToActivity(this)
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val light = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (UiMotion.animationsEnabled(this)) {
            overridePendingTransition(R.anim.m3_fade_hold, R.anim.m3_fade_hold)
        }
    }
}
