package io.github.midihl.wastickersimporter

import android.app.Application

class ImporterApplication : Application() {
    val telegramImports by lazy { io.github.midihl.wastickersimporter.telegram.TelegramImports(this) }
    override fun onCreate() {
        super.onCreate()
        ThemeSettings.applyNightMode(this)
    }
}
