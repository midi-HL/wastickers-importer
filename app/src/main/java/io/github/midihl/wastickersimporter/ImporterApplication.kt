package io.github.midihl.wastickersimporter

import android.app.Application

class ImporterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeSettings.applyNightMode(this)
        ThemeSettings.applyToApplication(this)
    }
}
