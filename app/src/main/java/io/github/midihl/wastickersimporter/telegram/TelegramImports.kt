package io.github.midihl.wastickersimporter.telegram

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlin.coroutines.coroutineContext

class TelegramImports(context: Context) {
    private val appContext = context.applicationContext
    val workers = ImportWorkerPool(TelegramImportSettings.threads(appContext))
    val queue = TelegramTaskQueue<TelegramImportCoordinator.Result>(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    fun add(shortName: String, token: String, retryId: String? = null): Boolean = queue.add(shortName, retryId) { onProgress ->
        val owner = coroutineContext.job
        TelegramImportCoordinator(appContext, token, workers, cancelled = { !owner.isActive }).fetchAndImport(shortName, onProgress)
    }

    fun setThreads(count: Int) {
        TelegramImportSettings.setThreads(appContext, count)
        workers.resize(count)
    }
}
