package io.github.midihl.wastickersimporter

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeSettings.applySeedToActivity(this)
        super.onCreate(savedInstanceState)
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (UiMotion.animationsEnabled(this)) {
            overridePendingTransition(R.anim.m3_fade_hold, R.anim.m3_fade_hold)
        }
    }
}
