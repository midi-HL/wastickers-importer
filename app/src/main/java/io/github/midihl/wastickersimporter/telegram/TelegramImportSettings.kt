package io.github.midihl.wastickersimporter.telegram

import android.content.Context

object TelegramImportSettings {
    fun threads(context: Context): Int = context.getSharedPreferences("telegram_import", Context.MODE_PRIVATE)
        .getInt("threads", ImportWorkerPool.DEFAULT_THREADS).coerceIn(ImportWorkerPool.MIN_THREADS, ImportWorkerPool.MAX_THREADS)

    fun setThreads(context: Context, count: Int) {
        context.getSharedPreferences("telegram_import", Context.MODE_PRIVATE).edit()
            .putInt("threads", count.coerceIn(ImportWorkerPool.MIN_THREADS, ImportWorkerPool.MAX_THREADS)).apply()
    }
}
